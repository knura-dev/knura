#!/usr/bin/env bash
# Tries the Knura Helm chart on a throwaway local Kubernetes cluster (kind), with NXP's public
# example key, never your real keys. Run it from anywhere in the repository:
#
#   deploy/kind/test.sh            build the images, start the cluster, install and test
#   deploy/kind/test.sh --clean    delete the test cluster
#
# Needs kind, kubectl, helm, openssl, curl and Docker or Podman (KIND_EXPERIMENTAL_PROVIDER=podman
# for kind on Podman). SKIP_BUILD=1 reuses images you already built; NODE_IMAGE picks kind's
# Kubernetes node image.
set -euo pipefail

CLUSTER=${CLUSTER:-knura-test}
NAMESPACE=knura-test
RELEASE=knura
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
# Spelled out with docker.io/ so Podman, which would add localhost/, names them as the chart expects.
KNURD_IMAGE=docker.io/knura/knurd:dev
MATTER_IMAGE=docker.io/knura/knurgn-matter:dev
# NXP's AN10922 example master key and a tap made with it (UID 04DE5F1EACC040, counter 1). Public:
# they protect nothing, which is the point of a test.
MASTER_KEY=00112233445566778899AABBCCDDEEFF
TAP='e=F5D049F6CEC31568AFAA3409EC06A9E8&c=53D5EC307125E7A6'
UID_HEX=04DE5F1EACC040

step() { printf '\n==> %s\n' "$*"; }
fail() { printf '\nFAILED: %s\n' "$*" >&2; exit 1; }

if [[ ${1:-} == --clean ]]; then
    kind delete cluster --name "$CLUSTER"
    exit 0
fi

for tool in kind kubectl helm openssl curl; do
    command -v "$tool" >/dev/null || fail "$tool is not installed (see deploy/README.md)"
done
if [[ -n ${ENGINE:-} ]]; then
    :
elif [[ ${KIND_EXPERIMENTAL_PROVIDER:-} == podman ]] || ! command -v docker >/dev/null; then
    ENGINE=podman
else
    ENGINE=docker
fi
command -v "$ENGINE" >/dev/null || fail "neither docker nor podman is installed"

if [[ -z ${SKIP_BUILD:-} ]]; then
    step "Building knurd and the images with $ENGINE"
    (cd "$ROOT" && ./gradlew -q :knurd:installDist)
    "$ENGINE" build -f "$ROOT/knurd/Dockerfile" -t "$KNURD_IMAGE" "$ROOT"
    "$ENGINE" build -t "$MATTER_IMAGE" "$ROOT/knurgn-matter"
fi

if ! kind get clusters | grep -qx "$CLUSTER"; then
    step "Starting the kind cluster $CLUSTER"
    kind create cluster --name "$CLUSTER" --wait 120s ${NODE_IMAGE:+--image "$NODE_IMAGE"}
fi
kubectl config use-context "kind-$CLUSTER" >/dev/null

step "Copying the images into the cluster"
WORK=$(mktemp -d)
PF_PID=
cleanup() {
    [[ -n $PF_PID ]] && kill "$PF_PID" 2>/dev/null || true
    rm -rf "$WORK"
}
trap cleanup EXIT
for image in "$KNURD_IMAGE" "$MATTER_IMAGE"; do
    "$ENGINE" save -o "$WORK/image.tar" "$image"
    kind load image-archive "$WORK/image.tar" --name "$CLUSTER"
    rm -f "$WORK/image.tar"
done

step "Making the keys Secret (example key and a fresh webhook secret)"
kubectl create namespace "$NAMESPACE" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
(
    umask 077
    secret="whsec_$(openssl rand -base64 32)"
    printf '%s\n' "$MASTER_KEY" > "$WORK/master.key"
    printf 'home=%s\n' "$secret" > "$WORK/knurgn-secrets.properties"
    printf '%s\n' "$secret" > "$WORK/knurgn-matter-secret"
)
kubectl --namespace "$NAMESPACE" create secret generic knura-keys \
    --from-file="$WORK/master.key" \
    --from-file="$WORK/knurgn-secrets.properties" \
    --from-file="$WORK/knurgn-matter-secret" \
    --dry-run=client -o yaml | kubectl apply -f - >/dev/null
rm -f "$WORK"/master.key "$WORK"/knurgn-*

step "Installing the chart"
cat > "$WORK/values.yaml" <<VALUES
knots:
  - uid: "$UID_HEX"
    name: desk-candle
    actions: [home]
matter:
  enabled: true
  labels:
    desk-candle: Desk candle
VALUES
helm upgrade --install "$RELEASE" "$ROOT/deploy/helm/knura" --namespace "$NAMESPACE" \
    -f "$WORK/values.yaml" --wait --timeout 3m

pod() { kubectl --namespace "$NAMESPACE" get pod -l app.kubernetes.io/instance="$RELEASE" \
    -o jsonpath='{.items[0].metadata.name}'; }
forward() {
    [[ -n $PF_PID ]] && kill "$PF_PID" 2>/dev/null || true
    kubectl --namespace "$NAMESPACE" port-forward "service/$RELEASE" 18424:8424 >/dev/null 2>&1 &
    PF_PID=$!
    for _ in $(seq 30); do
        curl -fsS http://127.0.0.1:18424/health >/dev/null 2>&1 && return 0
        sleep 1
    done
    fail "knurd did not answer through port-forward"
}
tap() { curl -sS "http://127.0.0.1:18424/tap?$TAP"; }
expect() { [[ $2 == *"\"status\":\"$1\""* ]] || fail "expected $1, got: $2"; echo "  $1: $2"; }

if [[ $(kubectl --namespace "$NAMESPACE" exec "$(pod)" -c knurd -- \
        sh -c 'ls /var/lib/knura/knurd-counters.properties 2>/dev/null || true') ]]; then
    step "Counters from an earlier run found; starting with a fresh volume"
    helm uninstall "$RELEASE" --namespace "$NAMESPACE" --wait
    kubectl --namespace "$NAMESPACE" delete pvc "$RELEASE-data" --wait
    helm upgrade --install "$RELEASE" "$ROOT/deploy/helm/knura" --namespace "$NAMESPACE" \
        -f "$WORK/values.yaml" --wait --timeout 3m
fi

step "Tapping"
forward
expect ok "$(tap)"
expect replay "$(tap)"

step "Checking the Matter bridge got the tap"
for _ in $(seq 15); do
    logs=$(kubectl --namespace "$NAMESPACE" logs "$(pod)" -c matter)
    [[ $logs == *"tap $UID_HEX:1:"* ]] && break
    sleep 1
done
grep "tap $UID_HEX:1:" <<<"$logs" || fail "the bridge did not log the tap: $logs"
grep -q "refused" <<<"$logs" && fail "the bridge refused a tap: $logs"

step "Restarting the pod: replay protection must survive"
kubectl --namespace "$NAMESPACE" delete pod "$(pod)" --wait
kubectl --namespace "$NAMESPACE" rollout status "deployment/$RELEASE" --timeout 3m
forward
expect replay "$(tap)"

step "Checking the key files are private inside the pod"
perms=$(kubectl --namespace "$NAMESPACE" exec "$(pod)" -c knurd -- stat -c '%a %n' \
    /run/knura/master.key /run/knura/knurgn-secrets.properties /run/knura/knurgn-matter-secret)
echo "$perms" | sed 's/^/  /'
[[ $(awk '$1 != "600"' <<<"$perms") == "" ]] || fail "key files must be 600"

step "helm test"
helm test "$RELEASE" --namespace "$NAMESPACE"

printf '\nAll checks passed. The cluster is still running; look around with\n'
printf '  kubectl --namespace %s get all\n' "$NAMESPACE"
printf 'and delete it with: deploy/kind/test.sh --clean\n'

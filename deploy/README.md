# Running Knura on Kubernetes

The Helm chart in [`helm/knura`](helm/knura) runs knurd (with knurgN) in one pod, and optionally
the knurgN Matter bridge next to it in the same pod.

| Piece | Where it runs | Why |
| --- | --- | --- |
| knurd + knurgN | the cluster | verifies taps and runs actions |
| knurgn-matter | the cluster, same pod as knurd (`matter.enabled`) | knurgN only sends taps to a bridge on 127.0.0.1 |
| knurctl | your computer | it needs the USB NFC reader |
| tap.knura.io resolver | AWS Lambda | it is shared by every Knura owner, not part of your install |

## Try it on a throwaway cluster

[`kind/test.sh`](kind/test.sh) builds the images, starts a local [kind](https://kind.sigs.k8s.io)
cluster, installs the chart with NXP's public example key and checks that a tap verifies, that
the replay is refused (also after a restart), that the Matter bridge got the tap and that the key
files inside the pod are private. CI runs the same script on every pull request.

```sh
deploy/kind/test.sh
deploy/kind/test.sh --clean    # delete the test cluster
```

## Images

```sh
./gradlew :knurd:installDist
docker build -f knurd/Dockerfile -t knura/knurd:dev .
docker build -t knura/knurgn-matter:dev knurgn-matter
```

Both run as user 10001, and only the built code goes into them: the `.dockerignore` files keep
`knurd.properties`, keys, counters and Matter storage on your computer. Settings and keys come
in at run time from the chart. The images aren't published to a registry yet; load them into your
cluster (`kind load`, `k3s ctr images import`) or push them to your own registry and set
`image.*.repository`.

## Keys: a Secret you make yourself

The chart never takes keys as values, so they can't end up in a values file, in Git or in Helm's
release history. Make a Secret named `knura-keys` (or set `existingSecret`) with:

| Key | What | Needed |
| --- | --- | --- |
| `master.key` | the `master.key` value from `knurd.properties`, 32 hex digits | always |
| `knurgn-secrets.properties` | your existing knurgN secrets file (`<id>=whsec_...` lines) | when knots have actions |
| `knurgn-matter-secret` | the Matter bridge's `whsec_` secret alone (your `knurgn-matter-secret` file) | with `matter.enabled` |

From the folder with your `knurd.properties`:

```sh
mkdir -m 700 k8s-keys
(umask 077; sed -n 's/^master\.key=//p' knurd.properties > k8s-keys/master.key)
kubectl create namespace knura
kubectl --namespace knura create secret generic knura-keys \
    --from-file=k8s-keys/master.key \
    --from-file=knurgn-secrets.properties
rm -r k8s-keys
```

`resolver.key` stays on your computer: knurd never uses it, only `knurctl resolver register` does.

Kubernetes stores Secrets unencrypted in its database unless the cluster turns on
[encryption at rest](https://kubernetes.io/docs/tasks/administer-cluster/encrypt-data/), and anyone
allowed to read Secrets in the namespace can read the master key. Keep the namespace to yourself.

## Settings

Write your own values file and install with `helm install knura deploy/helm/knura --namespace knura
-f my-values.yaml`. Everything is described in [`values.yaml`](helm/knura/values.yaml):

```yaml
publicUrl: https://fedora.tail113e80.ts.net
resolver:            # the resolver.* lines from knurd.properties, minus resolver.key
  owner: sj5iucyxjz
  publicKey: <64 hex digits>
knots:
  - uid: "042D6B02936980"
    name: desk-candle
    actions: [desk-webhook]
webhooks:
  desk-webhook: https://example.org/knura
```

The chart checks knots and actions at install time, so a typo fails `helm install` rather than
the pod.

## How it is locked down

- Exactly one knurd, and never two at once (`Recreate`): replay protection keeps each tag's last
  counter in one file, and two copies would each accept the same tap once.
- Counters live on a PersistentVolumeClaim that `helm uninstall` keeps, so reinstalling can't
  reset replay protection by accident. Moving an existing knurd? Copy its
  `knurd-counters.properties` in first (`kubectl cp`), or tap links it has already accepted will
  work once more.
- An init container copies the keys from the Secret into memory (`emptyDir` with
  `medium: Memory`) as owner-only files: Kubernetes makes Secret files readable by the pod's group,
  and knurd and the bridge refuse key files that others can read.
- Non-root user, read-only root filesystem, all Linux capabilities dropped, no privilege
  escalation, the runtime's default seccomp profile and no Kubernetes API token in the pod.

## Phones and HTTPS

Phones need an HTTPS address that never changes (it is written onto tags). Either turn on
`ingress` with a TLS certificate for your host name, or keep a tunnel such as Tailscale Funnel in
front of the Service. Either way, set `publicUrl` to that address.

## The Matter bridge

With `matter.enabled`, knots whose actions include `matter.id` (default `home`) show up as
contact sensors. Matter finds devices with mDNS on your home network, which doesn't reach into a
cluster's own pod network, so the pod uses the node's network (`matter.hostNetwork`). The node
must be on the same home network as your smart-home hub and have IPv6, and its firewall must let
in mDNS and UDP 5540. The pairing code is in the bridge's log:
`kubectl --namespace knura logs deployment/knura -c matter`.

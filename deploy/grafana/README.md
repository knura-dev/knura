# Grafana dashboard for knurd

A ready-made Grafana dashboard for knurd's Prometheus metrics: taps by result, forged or copied
links, tap check time, actions, and Java health (memory, threads, garbage collection, CPU, open
files). It's optional. knurd's own dashboard at http://127.0.0.1:8425 needs none of this.

First turn the metrics page on: add `metrics.port=9425` to `knurd.properties` and restart knurd
(see [docs/metrics.md](../../docs/metrics.md)).

## Already have Grafana?

In Grafana, go to **Dashboards → New → Import**, upload
[`provisioning/dashboards/knurd.json`](provisioning/dashboards/knurd.json), and pick your
Prometheus data source. Your Prometheus needs a `knurd` job scraping knurd, as in
[`prometheus.yml`](prometheus.yml).

## Starting from nothing (Linux, podman or Docker)

From this folder, start Prometheus and Grafana. Both use the host network, so Prometheus reaches
knurd on 127.0.0.1:9425 (the `:Z` lets them read the files on Fedora and other SELinux systems):

```sh
podman run -d --name prometheus --network host \
  -v ./prometheus.yml:/etc/prometheus/prometheus.yml:Z \
  -v prometheus-data:/prometheus docker.io/prom/prometheus

podman run -d --name grafana --network host \
  -v ./provisioning:/etc/grafana/provisioning:Z \
  -v grafana-data:/var/lib/grafana docker.io/grafana/grafana
```

`docker` works the same way. Open http://localhost:3000, sign in as admin / admin, choose a new
password, then open **Dashboards → Knura → Knura: knurd**. On the first start Grafana downloads
plugin updates, so the page can take a minute to load.

If the panels stay empty, check http://localhost:9090/targets: the `knurd` target should be UP.

Stop them with `podman stop grafana prometheus` and start them again with
`podman start prometheus grafana`.

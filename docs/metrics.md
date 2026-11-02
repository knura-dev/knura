# Metrics for Prometheus

knurd can serve a `/metrics` page in the Prometheus format, so you can chart it in Grafana and get
alerts when something goes wrong. It's off by default. Logs tell you what happened to one tap
([Troubleshooting with logs](logs.md)); metrics tell you how knurd is doing over time.

## Turn it on

Add a port to `knurd.properties` and restart knurd:

```properties
metrics.port=9425
```

Then check it:

```sh
curl http://127.0.0.1:9425/metrics
```

By default it listens on this computer only. If Prometheus runs on another computer on your
network, also set this computer's LAN or Tailscale address (or `0.0.0.0` in a container):

```properties
metrics.listen.host=192.168.1.20
```

The page has no login, which is what Prometheus expects. That's safe because it holds no secrets
and nothing about any one tag, but keep it off the internet anyway: knurd only listens on this
computer or a local network address, answers only callers from those networks, and refuses
requests that came through a proxy or tunnel. Don't point Tailscale Funnel or a port forward at it.

## Point Prometheus at it

```yaml
scrape_configs:
  - job_name: knurd
    static_configs:
      - targets: ["192.168.1.20:9425"]
```

## What it shows

Only totals across all tags. No tag UIDs, tag names, connector names or addresses.

| Metric | What it means |
| --- | --- |
| `knurd_taps_total{result}` | Taps checked. `result` is `ok`, `malformed`, `rejected` (forged or unknown tag), `replay` (a copied link), `error` (knurd failed while checking) or `throttled` (too many failed taps from one address). |
| `knurd_tap_verify_seconds` | A histogram of how long checking a tap took. |
| `knurd_actions_started_total` | Actions started by genuine taps. |
| `knurd_actions_total{outcome}` | How actions ended: `delivered`, `refused` (the other end said no) or `expired` (no answer after retrying). Tests from the dashboard aren't counted. |
| `knurd_tags_with_actions` | Tags that have actions set up. |
| `jvm_memory_used_bytes{area}`, `jvm_memory_committed_bytes`, `jvm_memory_max_bytes` | Memory. `heap` is knurd's data, `nonheap` is code and classes. |
| `jvm_threads_current`, `jvm_threads_daemon`, `jvm_threads_peak` | Platform threads. knurd answers requests on virtual threads, which aren't counted. |
| `jvm_gc_collections_total{gc}`, `jvm_gc_collection_seconds_total{gc}` | Garbage collections and the time they took. |
| `jvm_classes_loaded`, `jvm_info` | Loaded classes, and which Java runs knurd. |
| `process_uptime_seconds`, `process_start_time_seconds` | How long knurd has been running. A reset means it restarted. |
| `process_cpu_seconds_total`, `process_available_processors` | CPU used, and the cores Java may use. |
| `process_open_fds`, `process_max_fds` | Open files and connections, and the limit (Linux and macOS). |

## Useful queries and alerts

```promql
# Taps per minute, by result
sum by (result) (rate(knurd_taps_total[5m])) * 60

# Forged or copied links in the last hour (someone may be trying old links)
increase(knurd_taps_total{result=~"rejected|replay"}[1h])

# Slowest 5% of tap checks, in seconds
histogram_quantile(0.95, sum by (le) (rate(knurd_tap_verify_seconds_bucket[5m])))

# Share of actions that didn't arrive
sum(rate(knurd_actions_total{outcome!="delivered"}[15m])) / sum(rate(knurd_actions_total[15m]))

# Heap use as a share of the limit
jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"}
```

Good starting alerts: knurd down (`up{job="knurd"} == 0` for 5 minutes), any `expired` actions,
heap above 90% for 10 minutes, and open files above 80% of the limit.

# OpenSearch (Podman) — Start / Stop

Single-node OpenSearch, security disabled (no auth, HTTP), exposed on host port **50140**.
Run all commands from the project root (`C:\Harsh Projects\rag`).

## Prerequisite

```shell
podman machine start
```

## Start

```shell
podman compose -f docker/docker-compose.yml up -d
```

(Alternative if `podman compose` is unavailable: `podman-compose -f docker/docker-compose.yml up -d`)

## Verify

```shell
podman compose -f docker/docker-compose.yml ps
curl http://localhost:50140
curl http://localhost:50140/_cluster/health?pretty
```

## Logs

```shell
podman compose -f docker/docker-compose.yml logs -f opensearch
```

## Stop (keeps data)

```shell
podman compose -f docker/docker-compose.yml stop
```

## Restart a stopped cluster

```shell
podman compose -f docker/docker-compose.yml start
```

## Stop and remove containers (keeps data volume)

```shell
podman compose -f docker/docker-compose.yml down
```

## Stop and delete all data (destructive)

```shell
podman compose -f docker/docker-compose.yml down -v
```

## Troubleshooting

If the container exits on startup (`max virtual memory areas vm.max_map_count too low`):

```shell
wsl -d podman-machine-default sysctl -w vm.max_map_count=262144
```

## Useful index checks

```shell
curl http://localhost:50140/_cat/indices?v
curl http://localhost:50140/rag-chunks/_count
curl http://localhost:50140/rag-chunks/_mapping?pretty
```

#!/usr/bin/env bash
# Muestra los miembros de etcd, quién es el líder Raft y el término actual.
# Pensado para el clúster kind-ha (sd-ha). Uso: ./etcd-status.sh [nodo]
# En Git Bash (Windows) evita que /etc/... se convierta en C:/Program Files/Git/etc/...
export MSYS_NO_PATHCONV=1
NODE="${1:-sd-ha-control-plane}"
kubectl -n kube-system exec "etcd-${NODE}" -- etcdctl \
  --endpoints=https://127.0.0.1:2379 \
  --cacert=/etc/kubernetes/pki/etcd/ca.crt \
  --cert=/etc/kubernetes/pki/etcd/server.crt \
  --key=/etc/kubernetes/pki/etcd/server.key \
  endpoint status --cluster -w table

#!/usr/bin/env bash
# Demo de control de concurrencia optimista (tema 5) con resourceVersion.
# Dos "clientes" leen el mismo objeto; el primero que escribe gana,
# el segundo recibe 409 Conflict porque su versión está obsoleta.
# Requiere jq.
set -u
NS=demo
kubectl -n "$NS" delete configmap contador --ignore-not-found >/dev/null
kubectl -n "$NS" create configmap contador --from-literal=valor=0 >/dev/null

echo "== Cliente A y cliente B leen el objeto (misma resourceVersion)"
kubectl -n "$NS" get configmap contador -o json > /tmp/cliente-a.json
cp /tmp/cliente-a.json /tmp/cliente-b.json
echo "   resourceVersion leída: $(jq -r .metadata.resourceVersion /tmp/cliente-a.json)"
read -rp "   [Enter] para que escriba A"

echo "== A escribe valor=1"
jq '.data.valor="1"' /tmp/cliente-a.json | kubectl replace -f -
echo "   resourceVersion actual: $(kubectl -n "$NS" get cm contador -o jsonpath='{.metadata.resourceVersion}')"
read -rp "   [Enter] para que escriba B con su versión antigua"

echo "== B escribe valor=2 con la resourceVersion que leyó"
jq '.data.valor="2"' /tmp/cliente-b.json | kubectl replace -f - || true

echo "== Valor final: $(kubectl -n "$NS" get cm contador -o jsonpath='{.data.valor}')"

# Kubernetes en Sistemas Distribuidos

Repositorio de las demos de la charla sobre Kubernetes en la asignatura **Sistemas Distribuidos** (42322, 3º del Grado en Ingeniería Informática, ESIIAB). Con él puedes repetir en tu ordenador todo lo que viste en clase.

Hay tres demos:

| Demo | Qué vas a ver | Temas de la asignatura |
|---|---|---|
| 1 · Tu servidor en el clúster | Servidor TCP en Java con 3 réplicas, Service, DNS, fallos y grupos | 2 (comunicación) |
| 2 · Por dentro | Leases, Raft en etcd y conflicto de `resourceVersion` | 4 (elecciones, exclusión mutua) y 5 (concurrencia) |
| 3 · Despliegue automático | `git push` → GitHub Actions → GHCR → Argo CD | Reconciliación (tema 1) |

## Qué hay en el repositorio

```
app/src/EchoServer.java   Servidor de eco TCP; responde con el nombre del pod y la versión
app/src/EchoClient.java   Cliente que abre una conexión nueva cada 0,5 s
app/Dockerfile            Imagen multi-etapa (JDK para compilar, JRE para ejecutar)
k8s/                      Manifiestos: Deployment + Service, StatefulSet + Service headless
cluster/kind.yaml         Clúster básico: 1 control plane + 2 workers
cluster/kind-ha.yaml      Clúster HA: 3 control planes (3 miembros etcd) para la demo de Raft
argocd/application.yaml   Aplicación de Argo CD que vigila k8s/
.github/workflows/        Pipeline de CI/CD
scripts/conflicto.sh      Demo de control de concurrencia optimista
scripts/etcd-status.sh    Estado de etcd: líder Raft y término
```

## Antes de empezar

Necesitas Docker, [`kind`](https://kind.sigs.k8s.io/), `kubectl` y `jq`. Para la demo 3, además, `git` y una cuenta de GitHub.

En Windows, ejecuta los scripts de `scripts/` desde **Git Bash**.

1. **Clona el repositorio** y entra en la carpeta:
   ```bash
   git clone https://github.com/adrianbernal/k8s-sd-demo.git
   cd k8s-sd-demo
   ```
2. **Construye la imagen** del servidor con el mismo nombre que usan los manifiestos:
   ```bash
   docker build -t ghcr.io/adrianbernal/k8s-sd-demo/echo-server:v1 app/
   ```
3. **Crea el clúster y carga en él las imágenes**, para que no tenga que descargarlas:
   ```bash
   docker pull busybox:1.36
   kind create cluster --config cluster/kind.yaml
   kind load docker-image ghcr.io/adrianbernal/k8s-sd-demo/echo-server:v1 busybox:1.36 --name sd
   ```
4. **Clúster HA** (solo para la parte de Raft). Necesita unos 5–6 GB de RAM libres para Docker:
   ```bash
   kind create cluster --config cluster/kind-ha.yaml
   ```

---

## Demo 1 · Tu servidor en el clúster

La idea: *es el mismo servidor de sockets que programas en las prácticas; lo que cambia es quién lo arranca, lo vigila y lo hace localizable.*

Abre dos terminales: una para los comandos y otra para el cliente.

```bash
kubectl config use-context kind-sd
kubectl create namespace demo
kubectl apply -f k8s/deployment.yaml -f k8s/service.yaml
kubectl -n demo get pods -o wide        # 3 réplicas repartidas entre los workers
```

**Lanza el cliente** en la segunda terminal. Es un pod más, con la misma imagen:

```bash
kubectl -n demo run cliente --rm -it --restart=Never \
  --image=ghcr.io/adrianbernal/k8s-sd-demo/echo-server:v1 -- EchoClient echo 5000
```

Fíjate en dos cosas:
- El cliente se conecta a `echo`, un **nombre**, no a una IP. Lo resuelve el DNS del clúster (`echo.demo.svc.cluster.local`).
- Cada respuesta viene de un pod distinto: el Service **balancea por conexión TCP**. Piensa qué pasaría si el cliente reutilizara una sola conexión.

**Tolerancia a fallos.** Borra uno de los pods:

```bash
kubectl -n demo delete pod <uno-de-los-pods>
kubectl -n demo get pods -w             # aparece uno nuevo con otro nombre
```

El cliente apenas lo nota: el pod borrado deja de recibir conexiones nuevas y el Deployment crea otro para volver a 3 réplicas. Es el **bucle de reconciliación** en acción.

**Escalado:**

```bash
kubectl -n demo scale deployment echo --replicas=6
```

En unos segundos verás nombres nuevos en el cliente.

**Comunicación de grupos (tema 2.4).** Esta parte es independiente de la anterior: no hace falta parar el cliente ni quitar el Deployment.

```bash
kubectl apply -f k8s/grupo.yaml
kubectl -n demo get pods -l app=grupo     # espera a que grupo-0, grupo-1 y grupo-2 estén Running
kubectl -n demo run dns --rm -it --restart=Never --image=busybox:1.36 -- nslookup grupo.demo.svc.cluster.local
kubectl -n demo run dns --rm -it --restart=Never --image=busybox:1.36 -- nslookup grupo-0.grupo.demo.svc.cluster.local
```

Con un Service *headless* el DNS devuelve **las IPs de todos los miembros**, y cada uno tiene un nombre estable (`grupo-0`, `grupo-1`…). Es lo que necesita un algoritmo que tenga que hablar con cada miembro del grupo (elecciones, replicación, multidifusión).

Se usa el nombre completo porque el `nslookup` de busybox no completa bien los nombres cortos: con `grupo-0.grupo` da NXDOMAIN. Si ves el aviso `couldn't attach to pod/dns`, no pasa nada: el pod terminó antes de que `kubectl` se enganchara y te muestra igualmente la salida.

Para terminar: `Ctrl+C` en el cliente y `kubectl -n demo scale deployment echo --replicas=3`.

---

## Demo 2 · Por dentro: elecciones, consenso y concurrencia

### 1. Elección de líder con Leases (tema 4.4)

El scheduler y el controller-manager tienen varias copias en un clúster HA, pero solo una debe actuar. Lo resuelven con un **Lease**: un objeto que funciona como cerrojo con caducidad.

```bash
kubectl -n kube-system get lease
kubectl -n kube-system get lease kube-controller-manager -o yaml
```

Mira estos campos:
- `holderIdentity`: quién es el líder.
- `leaseDurationSeconds`: cuánto dura el cerrojo si no se renueva.
- `renewTime`: la última renovación.
- `leaseTransitions`: cuántas veces ha cambiado el líder.

Relación con el temario: es **exclusión mutua distribuida con un coordinador** (etcd) y **depende de relojes físicos** (tema 4.1). Si el reloj de un candidato va muy desviado, puede creer que el lease sigue vigente cuando ya ha caducado.

**Provoca un cambio de líder** en el clúster `sd`. Si quieres verlo en directo, deja antes en otra terminal `kubectl -n kube-system get lease kube-controller-manager -w`.

```bash
docker exec sd-control-plane mv /etc/kubernetes/manifests/kube-controller-manager.yaml /tmp/
sleep 20
docker exec sd-control-plane mv /tmp/kube-controller-manager.yaml /etc/kubernetes/manifests/
kubectl -n kube-system get lease kube-controller-manager -o yaml   # nueva holderIdentity, leaseTransitions +1
```

Como solo hay un control plane, el nombre del nodo no cambia: lo que cambia es el sufijo de `holderIdentity`, un identificador que cada proceso genera al arrancar. El proceso nuevo es otro candidato: espera a que caduque el lease del anterior (15 s sin renovarse) y entonces lo adquiere. Por eso se espera 20 s.

### 2. Raft en etcd (tema 4.4 y quórum)

Solo con el clúster HA:

```bash
kubectl config use-context kind-sd-ha
./scripts/etcd-status.sh
```

La tabla muestra los 3 miembros, cuál es el líder (`IS LEADER`) y el término (`RAFT TERM`). El término es un **contador lógico** de elecciones: cada nueva elección lo incrementa, y un mensaje con un término antiguo se descarta (la misma idea que los relojes lógicos de Lamport).

La tabla muestra IPs, no nombres de nodo. Para saber qué contenedor es el líder:

```bash
kubectl get nodes -o wide        # columna INTERNAL-IP
```

**Tumba al líder** sin cortarle la red al contenedor (usa `pause`, no `stop`). Sustituye `sd-ha-control-plane` por el nodo que sea líder y consulta el estado desde otro:

```bash
docker pause sd-ha-control-plane
./scripts/etcd-status.sh sd-ha-control-plane2
```

Hay un líder nuevo y el término ha subido. Con 3 miembros se tolera 1 fallo (la mayoría es 2).

**Pierde el quórum** pausando un segundo nodo:

```bash
docker pause sd-ha-control-plane2
kubectl get nodes          # se queda colgado: sin mayoría, etcd no acepta escrituras ni lecturas consistentes
docker unpause sd-ha-control-plane sd-ha-control-plane2
```

Mientras haya nodos en pausa, algún `kubectl` puede tardar porque el balanceador del clúster todavía le manda peticiones al nodo pausado. No es un fallo.

### 3. Control de concurrencia optimista (tema 5)

```bash
kubectl config use-context kind-sd
./scripts/conflicto.sh
```

Dos clientes leen el mismo objeto. El primero escribe sin problema y el segundo recibe un error de conflicto (HTTP 409) porque su `resourceVersion` está obsoleta. Es **control de concurrencia optimista**: no hay bloqueos; se comprueba la versión al escribir y quien pierde reintenta sobre la versión nueva. Por debajo, etcd lo hace con una transacción de tipo *compare-and-swap*.

Para pensar: ¿por qué Kubernetes elige concurrencia optimista y no bloqueos (2PL)? Pista: miles de clientes, conflictos poco frecuentes y clientes que pueden morir con un cerrojo cogido.

---

## Demo 3 · Despliegue automático con GitOps

Para hacer esta demo necesitas tu propia copia del repositorio, porque el pipeline escribe en él:

1. Haz un **fork** en GitHub y clónalo.
2. Sustituye `adrianbernal` por tu usuario de GitHub (en minúsculas) en `k8s/*.yaml` y `argocd/application.yaml`, y haz push.
3. En tu fork, activa GitHub Actions: en los forks vienen desactivadas, así que entra en la pestaña **Actions** y pulsa el botón para habilitar los workflows. Después, en Settings → Actions → General → Workflow permissions, marca *Read and write*.
4. Cambia algo en `app/` y haz push para que el pipeline publique la imagen. Después pon el paquete como **público** (GitHub → Packages → echo-server → Package settings → Change visibility), para que el clúster pueda descargarlo sin credenciales.

Instala Argo CD en el clúster `sd`:

```bash
kubectl config use-context kind-sd
kubectl create namespace argocd
kubectl apply -n argocd --server-side --force-conflicts -f https://raw.githubusercontent.com/argoproj/argo-cd/stable/manifests/install.yaml
```

El `--server-side` hace falta porque uno de los CRDs de Argo CD es demasiado grande para un `kubectl apply` normal.

Crea la aplicación:

```bash
kubectl apply -f argocd/application.yaml
kubectl -n argocd get applications          # SYNCED / HEALTHY
```

Para abrir la interfaz de Argo CD:

```bash
kubectl -n argocd port-forward svc/argocd-server 8080:443
kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath='{.data.password}' | base64 -d
```

Entra en https://localhost:8080 con el usuario `admin` y esa contraseña.

**El ciclo completo**, con el cliente de la demo 1 corriendo en otra terminal:

1. Cambia `VERSION = "v1"` por `"v2"` en `app/src/EchoServer.java`.
2. `git commit -am "v2" && git push`
3. Mira GitHub Actions: construye la imagen, la publica en GHCR y hace commit del nuevo tag en `k8s/deployment.yaml`.
4. Argo CD detecta el commit (revisa el repo cada ~3 minutos; pulsa **Refresh** para no esperar) y sincroniza.
5. En el cliente verás respuestas `v2` mezcladas con `v1` hasta que todas las réplicas se actualizan, **sin ningún ERROR**: es el *rolling update* con `maxUnavailable: 0` y la readiness probe.

**Autorreparación (selfHeal):**

```bash
kubectl -n demo scale deployment echo --replicas=1
kubectl -n demo get pods -w                 # Argo CD lo devuelve a 3
```

La idea final: el repositorio Git es el **estado deseado** y Argo CD es otro bucle de reconciliación, igual que los controladores de Kubernetes.

---

## Limpieza

```bash
kind delete cluster --name sd
kind delete cluster --name sd-ha
```

Si solo quieres liberar memoria sin borrar el clúster HA, páralo y arráncalo cuando lo necesites:

```bash
docker stop sd-ha-external-load-balancer sd-ha-control-plane sd-ha-control-plane2 sd-ha-control-plane3 sd-ha-worker
docker start sd-ha-external-load-balancer sd-ha-control-plane sd-ha-control-plane2 sd-ha-control-plane3 sd-ha-worker
```

## Si algo falla

- **La imagen no arranca (`ErrImagePull`)**: no la has cargado con `kind load` o el nombre no coincide exactamente con el del manifiesto.
- **Argo CD no sincroniza**: comprueba que `repoURL` es correcto y que el repo es público y tiene commits; pulsa **Refresh**.
- **El pipeline falla al hacer push**: revisa el paso 3 de la demo 3 (*Read and write*).

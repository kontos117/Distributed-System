#!/usr/bin/env bash
# Deploy the full MapReduce system to a running Minikube cluster.
# Usage: ./k8s/deploy.sh

set -euo pipefail

cd "$(dirname "$0")/.."

NAMESPACE=mapreduce

echo "==> Pointing Docker to Minikube's registry"
eval "$(minikube docker-env)"
MINIKUBE_IP=$(minikube ip)

echo "==> Building images inside Minikube"
docker build -t mapreduce/ui-service:latest      -f ui-service/Dockerfile      .
docker build -t mapreduce/manager-service:latest -f manager-service/Dockerfile .
docker build -t mapreduce/worker:latest          -f worker/Dockerfile          .

echo "==> Applying namespace"
kubectl apply -f k8s/namespace/namespace.yaml

echo "==> Setting MinIO public endpoint to Minikube IP ${MINIKUBE_IP}"
sed -i "s|http://[0-9.]*:30900|http://${MINIKUBE_IP}:30900|g" \
  k8s/minio/minio-statefulset.yaml \
  k8s/manager/manager-configmap.yaml

echo "==> Deploying infrastructure (Postgres, MinIO, Keycloak)"
kubectl apply -f k8s/postgres/
kubectl apply -f k8s/minio/
kubectl apply -f k8s/keycloak/

echo "==> Waiting for infrastructure to be ready"
kubectl rollout status statefulset/postgres -n "$NAMESPACE" --timeout=120s
kubectl rollout status statefulset/minio    -n "$NAMESPACE" --timeout=120s
kubectl rollout status deployment/keycloak  -n "$NAMESPACE" --timeout=180s

echo "==> Configuring Keycloak mapreduce realm"
KEYCLOAK_POD=$(kubectl get pod -n "$NAMESPACE" -l app=keycloak -o jsonpath='{.items[0].metadata.name}')
KCADM="kubectl exec -n $NAMESPACE $KEYCLOAK_POD -- /opt/keycloak/bin/kcadm.sh"
KCADM_AUTH="--no-config --server http://localhost:8080 --realm master --user admin --password admin-secret"

# Create the mapreduce realm if it doesn't exist (--import-realm only runs on a fresh DB)
if ! $KCADM get realms/mapreduce $KCADM_AUTH 2>/dev/null | grep -q mapreduce; then
  $KCADM create realms -s realm=mapreduce -s enabled=true $KCADM_AUTH
fi

# Create mapreduce-cli client if it doesn't exist
if ! $KCADM get clients -r mapreduce -q clientId=mapreduce-cli $KCADM_AUTH 2>/dev/null | grep -q mapreduce-cli; then
  $KCADM create clients -r mapreduce \
    -s clientId=mapreduce-cli -s publicClient=true \
    -s directAccessGrantsEnabled=true -s standardFlowEnabled=false \
    -s fullScopeAllowed=true $KCADM_AUTH
fi

# Create realm roles if they don't exist
for role in admin user; do
  if ! $KCADM get roles -r mapreduce $KCADM_AUTH 2>/dev/null | grep -q "\"$role\""; then
    $KCADM create roles -r mapreduce -s name="$role" $KCADM_AUTH
  fi
done

# Make admin a composite of realm-management roles
for mgmt_role in manage-users view-users query-users; do
  $KCADM add-roles -r mapreduce --rname admin \
    --cclientid realm-management --rolename "$mgmt_role" $KCADM_AUTH 2>/dev/null || true
done

# Remove required-field constraints from user profile (email/firstName/lastName)
# so username-only registration is not blocked at login
kubectl exec -n "$NAMESPACE" "$KEYCLOAK_POD" -- bash -c '
  TOKEN=$(curl -sf http://localhost:8080/realms/master/protocol/openid-connect/token \
    -d "grant_type=password&client_id=admin-cli&username=admin&password=admin-secret" \
    | python3 -c "import sys,json; print(json.load(sys.stdin)[\"access_token\"])")
  PROFILE=$(curl -sf http://localhost:8080/admin/realms/mapreduce/users/profile \
    -H "Authorization: Bearer $TOKEN")
  FIXED=$(echo "$PROFILE" | python3 -c "
import sys, json
p = json.load(sys.stdin)
for a in p.get(\"attributes\", []):
    a.pop(\"required\", None)
print(json.dumps(p))")
  curl -sf -X PUT http://localhost:8080/admin/realms/mapreduce/users/profile \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "$FIXED" > /dev/null
  echo "User profile updated"
'

echo "==> Deploying Manager Service"
kubectl apply -f k8s/manager/
kubectl rollout status statefulset/manager-service -n "$NAMESPACE" --timeout=300s

echo "==> Deploying UI Service"
kubectl apply -f k8s/ui/
kubectl rollout status deployment/ui-service -n "$NAMESPACE" --timeout=120s

echo ""
echo "==> Done. UI is accessible at:"
minikube service ui-service -n "$NAMESPACE" --url

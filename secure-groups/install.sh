helm uninstall my-secure-groups --namespace devspaces
docker save -o spring-demo.tar spring-demo:1.0.0
kind load image-archive spring-demo.tar --name devspaces
helm upgrade --install my-secure-groups ./helm --namespace devspaces
kubectl port-forward -n devspaces svc/my-secure-groups-service 8080:8080

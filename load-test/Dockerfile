# In-cluster load generator for Part 3's Kubernetes comparison and
# rolling-update test -- avoids kubectl port-forward's own fragility (it
# pins to one backend pod and does not follow when that exact pod is
# replaced, confirmed directly this session: a rollout killed the tunnel
# mid-run and produced ~13,500 client-side connection-refused failures that
# were an artifact of the tunnel, not of the Service or the rollout itself).
# Running k6 as a pod inside the cluster, addressing intake-service by its
# in-cluster DNS name, removes that hop entirely.
#   docker build -f load-test/Dockerfile -t payments/k6-load:local load-test
FROM grafana/k6:0.52.0
WORKDIR /test
COPY k6 ./k6
COPY corpus ./corpus
ENTRYPOINT ["k6", "run"]

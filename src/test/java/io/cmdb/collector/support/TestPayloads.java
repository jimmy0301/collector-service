package io.cmdb.collector.support;

/**
 * Builders for the raw event payloads the collectors produce (K8s native JSON wrapped in the {site,
 * cluster, fetchedAt, object} envelope). Every instance has its own random run suffix, so uids
 * never collide between tests that share one database.
 */
public final class TestPayloads {

  public final String run;
  public final String site;
  public final String clusterId;
  public final String depUid;
  public final String rsUid;
  public final String podUid;
  public final String eventUid;

  public TestPayloads(String run) {
    this.run = run;
    this.site = "S-" + run;
    this.clusterId = "cid-" + run;
    this.depUid = "dep-" + run;
    this.rsUid = "rs-" + run;
    this.podUid = "pod-" + run;
    this.eventUid = "ev-" + run;
  }

  public String k8s(String fetchedAt, String objectJson) {
    return """
        {"site":"%s","cluster":{"id":"%s","name":"plant-a"},"fetchedAt":"%s","object":%s}
        """
        .formatted(site, clusterId, fetchedAt, objectJson);
  }

  public String deployment(String rv) {
    return """
        {"apiVersion":"apps/v1","kind":"Deployment",
         "metadata":{"uid":"%s","name":"shop","namespace":"shop","resourceVersion":"%s",
                     "labels":{"cmdb.io/service-id":"SVC-%s","app":"shop"}},
         "spec":{"replicas":2},"status":{"readyReplicas":2}}
        """
        .formatted(depUid, rv, run);
  }

  public String replicaSet(String rv) {
    return """
        {"apiVersion":"apps/v1","kind":"ReplicaSet",
         "metadata":{"uid":"%s","name":"shop-rs","namespace":"shop","resourceVersion":"%s",
                     "ownerReferences":[{"kind":"Deployment","name":"shop","uid":"%s","controller":true}]},
         "spec":{"replicas":2},"status":{"replicas":2}}
        """
        .formatted(rsUid, rv, depUid);
  }

  public String pod(String rv, String phase, String deletionTimestamp) {
    String deletion =
        deletionTimestamp == null ? "" : ",\"deletionTimestamp\":\"" + deletionTimestamp + "\"";
    return """
        {"metadata":{"uid":"%s","name":"shop-abc","namespace":"shop","resourceVersion":"%s"%s,
           "ownerReferences":[{"kind":"ReplicaSet","name":"shop-rs","uid":"%s","controller":true}]},
         "status":{"phase":"%s",
           "containerStatuses":[
             {"name":"app","image":"acr.example.io/team/shop:1.2","imageID":"acr.example.io/team/shop@sha256:aaa%s",
              "ready":true,"restartCount":2,"state":{"running":{}},
              "lastState":{"terminated":{"reason":"OOMKilled","exitCode":137,"finishedAt":"2026-11-13T23:00:00Z"}}},
             {"name":"proxy","image":"acr.example.io/team/proxy:3","imageID":"docker-pullable://acr.example.io/team/proxy@sha256:bbb%s",
              "ready":false,"restartCount":3,"state":{"waiting":{"reason":"CrashLoopBackOff"}}}],
           "initContainerStatuses":[
             {"name":"init","image":"acr.example.io/team/init:1","imageID":"acr.example.io/team/init@sha256:ccc%s",
              "ready":true,"restartCount":0,"state":{"terminated":{"reason":"Completed","exitCode":0}}}]},
         "kind":"Pod","apiVersion":"v1"}
        """
        .formatted(podUid, rv, deletion, rsUid, phase, run, run, run);
  }

  public String event(String rv, int count, String lastTimestamp, String reason) {
    return """
        {"metadata":{"uid":"ev-%s","name":"shop-abc.1","namespace":"shop","resourceVersion":"%s",
                     "creationTimestamp":"2026-11-14T00:00:00Z"},
         "involvedObject":{"kind":"Pod","name":"shop-abc","uid":"%s","namespace":"shop"},
         "type":"Warning","reason":"%s","message":"Back-off restarting failed container",
         "count":%d,"firstTimestamp":"2026-11-14T00:00:00Z","lastTimestamp":"%s",
         "kind":"Event","apiVersion":"v1"}
        """
        .formatted(run, rv, podUid, reason, count, lastTimestamp);
  }

  public String list(String fetchedAt, String kind, String uidsJson) {
    return """
        {"site":"%s","cluster":{"id":"%s","name":"plant-a"},"namespace":"shop","kind":"%s",
         "uids":%s,"fetchedAt":"%s"}
        """
        .formatted(site, clusterId, kind, uidsJson, fetchedAt);
  }
}

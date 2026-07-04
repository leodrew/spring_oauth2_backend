# Istio stickiness — load-bearing auth dependency

Sessions and OAuth2 tokens are in-memory PER POD. The app's DestinationRule
consistentHash policy is what makes multi-pod operation correct. Removing or
weakening it reintroduces intermittent login 401s
(authorization_request_not_found) and broken API calls.

## Current state: useSourceIp (fragile)

The app's DestinationRule uses `consistentHash.useSourceIp: true`. This hashes
whatever source IP the app's sidecar sees:
- Via the ingress gateway that IP is often the GATEWAY POD's IP → one gateway
  replica funnels all its users to one app pod (hotspot), and multiple gateway
  replicas can route the SAME user to DIFFERENT app pods (stickiness broken).
- Corporate NAT: many users share one IP → one pod takes them all.
- Mobile clients: IP changes mid-session → user hops pods (one auth blip).

## Recommended: httpCookie

Replace the app DestinationRule's loadBalancer block with:

    trafficPolicy:
      loadBalancer:
        consistentHash:
          httpCookie:
            name: epmm-affinity
            ttl: 0s        # session cookie, issued by Envoy automatically

Cookie hashing is immune to gateway hops, NAT, and client IP changes.

## Verification

    kubectl get destinationrule -A -o yaml | grep -B4 -A6 consistentHash

Confirm the policy exists in EVERY environment and targets the APP's Service
host (a rule on the Keycloak service does not protect the app).

# jibe-tools-healthcheck

Send one or more `--healthcheck=<url>,<timeout-sec>[,name]` to the HealthCheckRunner

It will poll the endpoint until it answers something with an OK in, or the timeout triggers

I `name` is omitted it is using the host of the endpoint

## Example

```java
public class MyApp {
  public static void main(String...args) {
    // healthcheck before doing stuff...
    if ( !HealthCheckerRunner().run(asList(args)).allOK() ) {
        // failed
    }
    
    // do stuff
  }
```


Start MyApp

```
java ... MyApp --healthcheck=http://<healthcheck-url>,10
```
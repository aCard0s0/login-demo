# web-errors

The one copy of the `{"error": "..."}` body the frontend reads. A plain jar with one class; all four services
depend on it.

```
com.demo.web.errors   ErrorBodyAdvice   every rejection MVC makes, in the {error} shape
                      Bad               Bad.request("why"): the 400 a service throws from its own rules
```

`ErrorBodyAdvice` extends Spring's `ResponseEntityExceptionHandler`, which already knows every way MVC itself
says no -- a `ResponseStatusException`, a body that will not parse, a non-numeric path id, a wrong method, an
unknown path -- and would answer each with a `ProblemDetail` the frontend does not read. It overrides the one
method that builds the response, so all of them come back as `{error}` with the status they already had. A
body naming a value outside an enum gets a real sentence -- "unknown value 'X', expected one of [...]" --
because the pages show this text.

## Using it

It is abstract, not a bean: each service's own `@RestControllerAdvice` extends it and adds only its domain's
rejections, so there is one advice per service and no question of which of two handles an exception.

```java
@RestControllerAdvice
public class TodoExceptionAdvice extends ErrorBodyAdvice {}
```

## Tests

`ErrorBodyAdviceTests`, against a standalone MockMvc: a `Bad.request`, a body naming a value outside an
enum, a body that is not JSON and a wrong method all come back as `{error}`. Each service's
`ApiContractTests` then asserts the body on its own 401s and 403s.

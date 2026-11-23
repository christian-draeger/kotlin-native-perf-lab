import { Elysia } from "elysia";

const user = (id) => ({ id, name: "benchmark-user", active: true });

new Elysia({ serve: { hostname: Bun.env.BIND_HOST ?? "127.0.0.1" } })
  .get("/health", () => new Response("ok", { headers: { "Content-Type": "text/plain; charset=utf-8" } }))
  .get("/json", () => user(42))
  .get("/users/:id", ({ params, status }) => {
    const id = Number(params.id);
    if (!/^[+-]?[0-9]+$/.test(params.id) || id < -2147483648 || id > 2147483647) {
      return status(400);
    }
    return user(id);
  })
  .listen(Number(Bun.env.PORT ?? 8080));

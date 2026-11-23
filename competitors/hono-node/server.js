import { serve } from "@hono/node-server";
import { Hono } from "hono";

const user = (id) => ({ id, name: "benchmark-user", active: true });
const app = new Hono();

app.get("/health", (c) => c.text("ok"));
app.get("/json", (c) => c.json(user(42)));
app.get("/users/:id", (c) => {
  const value = c.req.param("id");
  const id = Number(value);
  if (!/^[+-]?[0-9]+$/.test(value) || id < -2147483648 || id > 2147483647) {
    return c.body(null, 400);
  }
  return c.json(user(id));
});

serve({ fetch: app.fetch, hostname: "127.0.0.1", port: Number(process.env.PORT ?? 8080) });

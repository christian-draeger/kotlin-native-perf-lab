using System.Globalization;
using System.Net;
using Microsoft.AspNetCore.Server.Kestrel.Core;

var builder = WebApplication.CreateBuilder(args);
builder.Logging.AddFilter("Microsoft.AspNetCore", LogLevel.Warning);
var address = IPAddress.Parse(Environment.GetEnvironmentVariable("BIND_HOST") ?? "127.0.0.1");
var port = int.Parse(Environment.GetEnvironmentVariable("PORT") ?? "8080");
builder.WebHost.ConfigureKestrel(options =>
    options.Listen(address, port, listener => listener.Protocols = HttpProtocols.Http1));

var app = builder.Build();
app.MapGet("/health", () => Results.Text("ok"));
app.MapGet("/json", () => new User(42, "benchmark-user", true));
app.MapGet("/users/{id}", (string id) =>
    int.TryParse(id, NumberStyles.AllowLeadingSign, CultureInfo.InvariantCulture, out var userId)
        ? Results.Ok(new User(userId, "benchmark-user", true))
        : Results.BadRequest());
app.Run();

record User(int Id, string Name, bool Active);

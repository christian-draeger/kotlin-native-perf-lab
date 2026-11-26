use axum::{
    body::{Body, Bytes},
    extract::Path,
    http::header,
    response::IntoResponse,
    routing::get,
    Json, Router,
};
use futures_util::stream;
use serde::Serialize;
use std::convert::Infallible;

mod application_mix;
#[cfg(feature = "upstream")]
mod upstream;

#[derive(Serialize)]
struct UserResponse {
    id: i32,
    name: &'static str,
    active: bool,
}

#[derive(Serialize)]
struct Catalog {
    id: i32,
    items: Vec<CatalogItem>,
}

#[derive(Serialize)]
struct CatalogItem {
    sku: String,
    name: String,
    description: &'static str,
    stock: Stock,
}

#[derive(Serialize)]
struct Stock {
    available: bool,
    units: i32,
}

fn user(id: i32) -> UserResponse {
    UserResponse {
        id,
        name: "benchmark-user",
        active: true,
    }
}

async fn health() -> ([(header::HeaderName, &'static str); 1], &'static str) {
    ([(header::CONTENT_TYPE, "text/plain; charset=UTF-8")], "ok")
}

async fn json() -> Json<UserResponse> {
    Json(user(42))
}

async fn user_by_id(Path(id): Path<i32>) -> Json<UserResponse> {
    Json(user(id))
}

async fn catalog(Path(id): Path<i32>) -> Json<Catalog> {
    let items = (0..32)
        .map(|index| CatalogItem {
            sku: format!("{id}-{index}"),
            name: format!("Café item {index}"),
            description: "Roasted in München and packed for delivery.",
            stock: Stock {
                available: index > 0,
                units: index * 3,
            },
        })
        .collect();
    Json(Catalog { id, items })
}

async fn stream_response() -> impl IntoResponse {
    let chunks = stream::iter((0..5).map(|_| Ok::<_, Infallible>(Bytes::from(vec![b'x'; 16 * 1024]))));
    (
        [(header::CONTENT_TYPE, "application/octet-stream")],
        Body::from_stream(chunks),
    )
}

#[tokio::main]
async fn main() {
    let port = std::env::var("PORT")
        .ok()
        .and_then(|value| value.parse::<u16>().ok())
        .unwrap_or(8080);
    let host = std::env::var("BIND_HOST").unwrap_or_else(|_| "127.0.0.1".to_owned());

    let app = Router::new()
        .route("/health", get(health))
        .route("/json", get(json))
        .route("/users/{id}", get(user_by_id));
    let app = match std::env::var("BENCHMARK_SUITE").as_deref() {
        Ok("application-mix") => application_mix::routes(app.route("/catalog/{id}", get(catalog))),
        Ok("application") => app.route("/catalog/{id}", get(catalog)),
        Ok("http2-stream") => app.route("/stream", get(stream_response)),
        _ => app,
    };
    #[cfg(feature = "upstream")]
    let app = {
        let client_only = std::env::var("UPSTREAM_CLIENT_ONLY").as_deref() == Ok("1");
        let serve_upstream = std::env::var("BENCHMARK_SUITE").as_deref() == Ok("application-upstream");
        if client_only || serve_upstream {
            let client = upstream::UpstreamClient::from_env();
            if client_only {
                client.run_client_only().await;
                return;
            }
            upstream::routes(app, client)
        } else {
            app
        }
    };

    let listener = tokio::net::TcpListener::bind((host.as_str(), port))
        .await
        .expect("Failed to bind HTTP listener");
    axum::serve(listener, app).await.expect("Server failed");
}

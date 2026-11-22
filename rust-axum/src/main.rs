use axum::{extract::Path, http::header, routing::get, Json, Router};
use serde::Serialize;

#[derive(Serialize)]
struct UserResponse {
    id: i32,
    name: &'static str,
    active: bool,
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

#[tokio::main]
async fn main() {
    let port = std::env::var("PORT")
        .ok()
        .and_then(|value| value.parse::<u16>().ok())
        .unwrap_or(8080);

    let app = Router::new()
        .route("/health", get(health))
        .route("/json", get(json))
        .route("/users/{id}", get(user_by_id));

    let listener = tokio::net::TcpListener::bind(("127.0.0.1", port))
        .await
        .expect("Failed to bind HTTP listener");
    axum::serve(listener, app).await.expect("Server failed");
}

use axum::{
    body::{Body, Bytes},
    http::{header, StatusCode},
    response::IntoResponse,
    routing::{get, post},
    Router,
};
use futures_util::{stream, StreamExt};
use std::{convert::Infallible, time::Duration};

const UPLOAD_LIMIT: usize = 64 * 1024;

pub fn routes(app: Router) -> Router {
    app.route("/upload", post(upload))
        .route("/download", get(download))
        .route("/wait", get(wait))
}

async fn upload(body: Body) -> Result<String, StatusCode> {
    let mut input = body.into_data_stream();
    let mut total = 0;
    let mut valid = true;
    while let Some(chunk) = input.next().await {
        let chunk = chunk.map_err(|_| StatusCode::BAD_REQUEST)?;
        total += chunk.len();
        if total > UPLOAD_LIMIT {
            return Err(StatusCode::PAYLOAD_TOO_LARGE);
        }
        valid = valid && chunk.iter().all(|&byte| byte == b'x');
    }
    if valid {
        Ok(total.to_string())
    } else {
        Err(StatusCode::BAD_REQUEST)
    }
}

async fn download() -> impl IntoResponse {
    let chunks = stream::unfold(0, |index| async move {
        let bytes = match index {
            0 => vec![b's'; 16 * 1024],
            1 => {
                tokio::time::sleep(Duration::from_millis(50)).await;
                vec![b't'; 16 * 1024 + 3]
            }
            _ => return None,
        };
        Some((Ok::<_, Infallible>(Bytes::from(bytes)), index + 1))
    });
    (
        [(header::CONTENT_TYPE, "application/octet-stream")],
        Body::from_stream(chunks),
    )
}

async fn wait() -> &'static str {
    tokio::time::sleep(Duration::from_millis(20)).await;
    "ready"
}

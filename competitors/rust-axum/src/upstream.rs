use axum::{
    body::Bytes,
    extract::Path,
    http::{header, Request, StatusCode},
    routing::get,
    Router,
};
use http_body_util::{BodyExt, Empty};
use hyper::client::conn::http1;
use hyper_util::rt::TokioIo;
use std::{error::Error, net::Ipv4Addr, time::Duration};
use tokio::{net::TcpStream, task::JoinHandle, time::timeout};

struct ConnectionTask(JoinHandle<Result<(), hyper::Error>>);

impl Drop for ConnectionTask {
    fn drop(&mut self) {
        self.0.abort();
    }
}

async fn ready(port: u16, scenario: &str) -> Result<bool, Box<dyn Error + Send + Sync>> {
    let stream = TcpStream::connect((Ipv4Addr::LOCALHOST, port)).await?;
    let (mut sender, connection) = http1::handshake(TokioIo::new(stream)).await?;
    let _connection_task = ConnectionTask(tokio::spawn(connection));
    let request = Request::builder()
        .uri(format!("/{scenario}"))
        .header(header::HOST, format!("127.0.0.1:{port}"))
        .header(header::CONNECTION, "close")
        .body(Empty::<Bytes>::new())?;
    let response = sender.send_request(request).await?;
    Ok(response.status() == StatusCode::OK
        && response.into_body().collect().await?.to_bytes() == "ready")
}

pub fn routes(app: Router, port: u16) -> Router {
    app.route(
        "/upstream/{scenario}",
        get(move |Path(scenario): Path<String>| async move {
            if !matches!(scenario.as_str(), "fast" | "slow" | "deadline" | "disconnect") {
                return (StatusCode::NOT_FOUND, "unknown-scenario");
            }
            match timeout(Duration::from_millis(150), ready(port, &scenario)).await {
                Ok(Ok(true)) => (StatusCode::OK, "origin-ready"),
                Ok(_) => (StatusCode::BAD_GATEWAY, "origin-unavailable"),
                Err(_) => (StatusCode::GATEWAY_TIMEOUT, "origin-deadline"),
            }
        }),
    )
}

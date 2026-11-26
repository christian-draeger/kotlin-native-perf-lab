use axum::{
    body::Bytes,
    extract::Path,
    http::{header, Request, StatusCode},
    routing::get,
    Router,
};
use http_body_util::{BodyExt, Empty};
use hyper::client::conn::http1::{self, SendRequest};
use hyper_util::rt::TokioIo;
use std::{
    error::Error,
    net::Ipv4Addr,
    sync::{Arc, Mutex},
    time::{Duration, Instant},
};
use tokio::{net::TcpStream, sync::Semaphore, task::JoinHandle, time::timeout};

const MAX_CONNECTIONS: usize = 64;

struct ConnectionTask(JoinHandle<Result<(), hyper::Error>>);

impl Drop for ConnectionTask {
    fn drop(&mut self) {
        self.0.abort();
    }
}

struct OriginConnection {
    sender: SendRequest<Empty<Bytes>>,
    _task: ConnectionTask,
}

impl OriginConnection {
    async fn open(port: u16) -> Result<Self, Box<dyn Error + Send + Sync>> {
        let stream = TcpStream::connect((Ipv4Addr::LOCALHOST, port)).await?;
        let (sender, connection) = http1::handshake(TokioIo::new(stream)).await?;
        Ok(Self {
            sender,
            _task: ConnectionTask(tokio::spawn(connection)),
        })
    }
}

struct Pool {
    permits: Semaphore,
    idle: Mutex<Vec<(Instant, OriginConnection)>>,
}

impl Pool {
    fn take_idle(&self) -> Option<OriginConnection> {
        let mut idle = self.idle.lock().expect("Upstream pool lock");
        while let Some((since, connection)) = idle.pop() {
            if since.elapsed() < Duration::from_secs(1) && !connection.sender.is_closed() {
                return Some(connection);
            }
        }
        None
    }

    fn return_idle(&self, connection: OriginConnection) {
        self.idle
            .lock()
            .expect("Upstream pool lock")
            .push((Instant::now(), connection));
    }
}

#[derive(Clone)]
pub struct UpstreamClient {
    port: u16,
    pool: Option<Arc<Pool>>,
}

impl UpstreamClient {
    pub fn from_env() -> Self {
        let port = std::env::var("BENCHMARK_UPSTREAM_PORT")
            .expect("Missing upstream port")
            .parse()
            .expect("Invalid upstream port");
        let mode = std::env::var("UPSTREAM_CONNECTION_MODE").unwrap_or_else(|_| "fresh".to_owned());
        let pool = match mode.as_str() {
            "fresh" => None,
            "keep-alive" => Some(Arc::new(Pool {
                permits: Semaphore::new(MAX_CONNECTIONS),
                idle: Mutex::new(Vec::with_capacity(MAX_CONNECTIONS)),
            })),
            _ => panic!("Unsupported upstream connection mode: {mode}"),
        };
        Self { port, pool }
    }

    async fn ready(&self, scenario: &str) -> Result<bool, Box<dyn Error + Send + Sync>> {
        let pool = self.pool.as_deref();
        let _permit = if let Some(pool) = pool {
            Some(pool.permits.acquire().await?)
        } else {
            None
        };
        let mut connection = match pool.and_then(Pool::take_idle) {
            Some(connection) => connection,
            None => OriginConnection::open(self.port).await?,
        };
        let mut request = Request::builder()
            .uri(format!("/{scenario}"))
            .header(header::HOST, format!("127.0.0.1:{}", self.port));
        if pool.is_none() {
            request = request.header(header::CONNECTION, "close");
        }
        if pool.is_some() {
            connection.sender.ready().await?;
        }
        let response = connection
            .sender
            .send_request(request.body(Empty::<Bytes>::new())?)
            .await?;
        let closes = response
            .headers()
            .get_all(header::CONNECTION)
            .iter()
            .filter_map(|value| value.to_str().ok())
            .flat_map(|value| value.split(','))
            .any(|token| token.trim().eq_ignore_ascii_case("close"));
        let ready = response.status() == StatusCode::OK
            && response.into_body().collect().await?.to_bytes() == "ready";
        if let Some(pool) = pool {
            if ready && !closes {
                pool.return_idle(connection);
            }
        }
        // Dropping a failed or incomplete lease aborts its Hyper driver and closes the socket.
        Ok(ready)
    }

    async fn request(&self, scenario: &str) -> (StatusCode, &'static str) {
        if expected_status(scenario).is_none() {
            return (StatusCode::NOT_FOUND, "unknown-scenario");
        }
        match timeout(Duration::from_millis(150), self.ready(scenario)).await {
            Ok(Ok(true)) => (StatusCode::OK, "origin-ready"),
            Ok(_) => (StatusCode::BAD_GATEWAY, "origin-unavailable"),
            Err(_) => (StatusCode::GATEWAY_TIMEOUT, "origin-deadline"),
        }
    }

    pub async fn run_client_only(&self) {
        let count: usize = std::env::var("UPSTREAM_REQUESTS")
            .expect("Missing UPSTREAM_REQUESTS")
            .parse()
            .expect("Invalid UPSTREAM_REQUESTS");
        assert!(
            (1..=1_000_000).contains(&count),
            "UPSTREAM_REQUESTS must be between 1 and 1,000,000"
        );
        let scenario = std::env::var("UPSTREAM_SCENARIO").unwrap_or_else(|_| "fast".to_owned());
        let expected = expected_status(&scenario).expect("Unsupported client-only scenario");
        let mut elapsed = Vec::with_capacity(count);
        let mut matched = 0;
        for _ in 0..count {
            let start = Instant::now();
            if self.request(&scenario).await.0 == expected {
                matched += 1;
            }
            elapsed.push(start.elapsed().as_micros());
        }
        elapsed.sort_unstable();
        let percentile = |percent: usize| elapsed[(percent * count).div_ceil(100) - 1];
        println!(
            "{{\"requests\":{count},\"expected\":{matched},\"unexpected\":{},\"p50_us\":{},\"p95_us\":{},\"p99_us\":{}}}",
            count - matched, percentile(50), percentile(95), percentile(99)
        );
        assert_eq!(matched, count, "Unexpected client-only responses");
    }
}

fn expected_status(scenario: &str) -> Option<StatusCode> {
    match scenario {
        "fast" | "slow" => Some(StatusCode::OK),
        "deadline" => Some(StatusCode::GATEWAY_TIMEOUT),
        "disconnect" => Some(StatusCode::BAD_GATEWAY),
        _ => None,
    }
}

pub fn routes(app: Router, client: UpstreamClient) -> Router {
    app.route(
        "/upstream/{scenario}",
        get(move |Path(scenario): Path<String>| {
            let client = client.clone();
            async move { client.request(&scenario).await }
        }),
    )
}

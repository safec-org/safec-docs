use axum::{routing::get, Router, response::Json};
use serde_json::{json, Value};

async fn handler() -> Json<Value> {
    Json(json!({ "message": "Hello, World!" }))
}

#[tokio::main]
async fn main() {
    let app = Router::new().route("/", get(handler));
    let port = std::env::var("PORT").unwrap_or_else(|_| "8086".to_string()); // default 8086
    let listener = tokio::net::TcpListener::bind(format!("0.0.0.0:{port}")).await.unwrap();
    println!("Rust (axum) server listening on {port}");
    axum::serve(listener, app).await.unwrap();
}

use axum::{routing::get, Router, response::Json};
use serde_json::{json, Value};

async fn handler() -> Json<Value> {
    Json(json!({ "message": "Hello, World!" }))
}

#[tokio::main]
async fn main() {
    let app = Router::new().route("/", get(handler));
    let listener = tokio::net::TcpListener::bind("0.0.0.0:8086").await.unwrap();
    println!("Rust (axum) server listening on 8086");
    axum::serve(listener, app).await.unwrap();
}

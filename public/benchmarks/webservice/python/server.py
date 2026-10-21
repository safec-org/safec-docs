import os

from fastapi import FastAPI
import uvicorn

app = FastAPI()

@app.get("/")
def root():
    return {"message": "Hello, World!"}

if __name__ == "__main__":
    port = int(os.environ.get("PORT", "8087"))
    print(f"Python (FastAPI+uvicorn) server listening on {port}")
    uvicorn.run(app, host="0.0.0.0", port=port, log_level="warning")

$ErrorActionPreference = "Stop"

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw "Docker is not installed or is not available in PATH. Install Docker Desktop, then rerun this script."
}

docker compose up -d

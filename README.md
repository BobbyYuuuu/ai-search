# SimpleSearch + AI (deploy-ready)

Text search, image analysis, and image generation in a tiny Java app.

## Run locally
```powershell
javac -d out src/com/example/*.java
$Env:OPENAI_API_KEY="sk-..."
$Env:OPENAI_MODEL="gpt-4o-mini"
$Env:OPENAI_IMAGE_MODEL="gpt-image-1"
$Env:PORT="8080"
java -cp out com.example.App
```

## Docker
```bash
docker build -t simple-search-ai-vision .
docker run -e OPENAI_API_KEY=sk-... -e OPENAI_MODEL=gpt-4o-mini -e OPENAI_IMAGE_MODEL=gpt-image-1 -p 8080:8080 simple-search-ai-vision
```

## Deploy to Render
1) Push this folder to GitHub.
2) In Render: **New > Web Service > Build from a repository**.
3) It uses the Dockerfile (render.yaml included).
4) Add environment variables:
   - `OPENAI_API_KEY` (required)
   - `OPENAI_MODEL` (optional, default gpt-4o-mini)
   - `OPENAI_IMAGE_MODEL` (optional, default gpt-image-1)
5) Deploy. Healthcheck: `GET /api/hello`.

> If you need uploads to persist, add a Render Disk and mount it at `/app/public/uploads`.

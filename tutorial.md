# Tutorial
This file provides all the commands used in the tutorial video to launch and run this modified version of Cytomine.

## Step 1 (Download the Repositories):

```
git clone git@github.com:ThibaudVanmechelen/Cytomine-community-edition.git
git clone git@github.com:ThibaudVanmechelen/bigpicture-cytomine-nginx.git
git clone git@github.com:ThibaudVanmechelen/Cytomine-sam.git
```

## Step 2 (Build Nginx Image):

```
cd bigpicture-cytomine-nginx
sudo docker build -t thibaud/nginx:0.1.0 .
```

## Step 3 (Build SAM2 API Image):

```
cd Cytomine-sam
sudo docker build -t thibaud/sam:0.1.0 .
```

## Step 4 (Run Cytomine Installer):

```
sudo docker run -v $(pwd):/install --user "$(id -u):$(id -g)" --rm -it cytomine/installer:latest deploy -s /install
```

## Step 5 (Declare the keys.toml file):

```
title = "Cytomine API keys"
host = "http://nginx"
public_key = "your_public_key"
private_key = "your_private_key"
```

The content must also be updated with the correct keys for the admin from `cytomine.yml`.

## Step 6 (Launch Cytomine):

Optional: Check if no process is already running on port 80
```
sudo lsof -i :80
sudo systemctl stop process_name (e.g apache2)
```

Launch Cytomine:
```
sudo docker compose -f docker-compose.yml -f docker-compose.override.yml -f docker-compose.override.dev.main.yml -f docker-compose.override.dev.ui.yml -f docker-compose.override.dev.core.yml --env-file .env --profile dev-core --profile dev-ims --profile dev-ui --profile dev-ae --profile dev up -d
```

## Step 7 (Launch Core service):

Inspect list of running containers:
```
sudo docker ps
```

Connect to the Core container:

```
sudo docker exec -it cytomine-ce-core-1 /bin/bash
```

Launch the Core service:

```
gradle bootRun
```

## Step 8 (Segment with SAM2):

See the video.

## Step 9 (Stop Cytomine):

```
sudo docker compose down
```
#!/bin/bash
TOKEN="REMOVED_TOKEN"
REPO="RD7890/Ryzix-OreESP-Fabric"
echo "Monitoring GitHub Actions for $REPO..."

while true; do
    # Get the latest run
    RUN_DATA=$(curl -s -H "Authorization: token $TOKEN" "https://api.github.com/repos/$REPO/actions/runs?per_page=1")
    STATUS=$(echo "$RUN_DATA" | grep -o '"status": "[^"]*' | head -1 | cut -d'"' -f4)
    CONCLUSION=$(echo "$RUN_DATA" | grep -o '"conclusion": "[^"]*' | head -1 | cut -d'"' -f4)
    RUN_ID=$(echo "$RUN_DATA" | grep -o '"id": [0-9]*' | head -1 | cut -d' ' -f2)

    if [ "$STATUS" == "completed" ]; then
        if [ "$CONCLUSION" == "success" ]; then
            echo "Build SUCCESS! Downloading artifact..."
            # Wait a few seconds for the release to be published by the workflow
            sleep 10
            # Download the jar from the latest release
            RELEASE_URL=$(curl -s -H "Authorization: token $TOKEN" "https://api.github.com/repos/$REPO/releases/latest" | grep "browser_download_url" | cut -d '"' -f 4)
            if [ -n "$RELEASE_URL" ]; then
                curl -sL -H "Authorization: token $TOKEN" "$RELEASE_URL" -o /storage/emulated/0/Ryzix-OreESP-Fabric/Release/ryzix-oreesp-v1.0.0.jar
                termux-notification --title "Ryzix-OreESP Ready!" --content "The v1.0.0 jar has been downloaded to the Release folder."
            else
                echo "Release URL not found. Workflow might not have created a release."
            fi
        else
            echo "Build FAILED! Fetching logs..."
            curl -sL -H "Authorization: token $TOKEN" "https://api.github.com/repos/$REPO/actions/runs/$RUN_ID/logs" -o /storage/emulated/0/Ryzix-OreESP-Fabric/logs/build_failed.zip
            unzip -q /storage/emulated/0/Ryzix-OreESP-Fabric/logs/build_failed.zip -d /storage/emulated/0/Ryzix-OreESP-Fabric/logs/
            termux-notification --title "Ryzix-OreESP Build Failed" --content "Logs saved to logs folder. Claude needs to fix it."
        fi
        break
    fi
    echo "Status: $STATUS (waiting 10s...)"
    sleep 10
done

#!/bin/sh
# Copy seed files into the persistent volume if they aren't there yet, then start the server.
set -e
for f in /opt/seed/*; do
  [ -e "/data/$(basename "$f")" ] || cp "$f" /data/
done
exec /opt/server/bin/server

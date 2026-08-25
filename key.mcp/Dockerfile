# Dockerfile for the KeY MCP server
# Build with: docker build -f key.mcp/Dockerfile -t key-mcp .
# Run with:   docker run -i --rm key-mcp

FROM eclipse-temurin:21-jre

WORKDIR /app

# The shadow jar must be built before building the image:
#   ./gradlew key.mcp:shadowJar
COPY key.mcp/build/libs/key-mcp-*-all.jar /app/key-mcp.jar

ENV KEY_MCP_LOG_LEVEL=INFO

# The server communicates over stdio; no ports are exposed.
ENTRYPOINT ["java", "-jar", "/app/key-mcp.jar"]

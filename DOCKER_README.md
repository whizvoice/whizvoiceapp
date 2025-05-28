# Docker Testing Environment

This directory contains a Docker setup for running Android unit tests in a consistent, isolated environment.

## Quick Start

1. **Setup Docker environment** (one-time):

   ```bash
   ./docker-setup.sh
   ```

2. **Run tests**:
   ```bash
   ./test-ci-locally.sh          # Uses Docker by default
   ./test-ci-locally.sh --docker # Explicitly use Docker
   ./test-ci-locally.sh --native # Use native environment
   ```

## Files Overview

- `Dockerfile` - Android development environment with pre-installed SDK
- `docker-compose.yml` - Service definitions for easy container management
- `.dockerignore` - Excludes unnecessary files from Docker build context
- `docker-setup.sh` - One-time setup script
- `test-ci-locally.sh` - Enhanced test script with Docker support

## Docker Advantages

✅ **Consistent Environment** - Same Android SDK version across all machines  
✅ **Pre-installed Dependencies** - Android SDK, build tools ready to go  
✅ **Isolation** - Tests run independently of local Android Studio setup  
✅ **Caching** - Gradle and Android caches persist between runs  
✅ **CI Parity** - Closer to GitHub Actions environment

## Performance Expectations

### First Run

- **Setup**: 5-10 minutes (downloads Android SDK)
- **Tests**: Similar to native (slight container overhead)

### Subsequent Runs

- **Setup**: 10-30 seconds (cached image)
- **Tests**: ~10-20% faster due to pre-warmed environment

## Commands Reference

### Basic Usage

```bash
# Run full test suite with Docker
./test-ci-locally.sh

# Run tests natively (no Docker)
./test-ci-locally.sh --native

# Get help
./test-ci-locally.sh --help
```

### Docker Compose Commands

```bash
# Run full test suite
docker-compose run --rm android-tests

# Run only unit tests
docker-compose run --rm android-tests ./gradlew test

# Run only lint
docker-compose run --rm android-tests ./gradlew lintDebug

# Interactive shell for debugging
docker-compose run --rm android-shell

# Build APK
docker-compose run --rm android-tests ./gradlew assembleDebug
```

### Direct Docker Commands

```bash
# Build image
docker build -t whizvoice-android-tests .

# Run tests
docker run --rm -v $(pwd):/app whizvoice-android-tests

# Interactive shell
docker run --rm -it -v $(pwd):/app whizvoice-android-tests /bin/bash
```

## Caching Strategy

The Docker setup uses persistent volumes for:

- **Gradle cache** (`~/.gradle`) - Dependencies and build cache
- **Android cache** (`~/.android`) - SDK and build tools cache

This means subsequent runs are much faster as dependencies don't need to be re-downloaded.

## Troubleshooting

### Docker Build Fails

```bash
# Clean build (removes cache)
docker-compose build --no-cache android-tests

# Or with direct Docker
docker build --no-cache -t whizvoice-android-tests .
```

### Tests Fail in Docker but Work Natively

```bash
# Check environment differences
docker-compose run --rm android-shell
./gradlew --version
env | grep -E "(JAVA|ANDROID|GRADLE)"
```

### Slow Performance

```bash
# Check Docker resource allocation
docker stats

# Increase Docker memory/CPU in Docker Desktop settings
# Recommended: 4GB RAM, 2+ CPUs
```

### Clear Caches

```bash
# Remove Docker volumes (clears all caches)
docker-compose down -v

# Remove Docker images
docker rmi whizvoice-android-tests
```

## Development Workflow

1. **Make code changes** in your IDE as usual
2. **Run tests** with `./test-ci-locally.sh`
3. **Debug issues** with `docker-compose run --rm android-shell`
4. **Push changes** - GitHub Actions will run similar environment

## GitHub Actions Integration

The Docker environment closely matches the GitHub Actions setup, providing better local/CI parity. The main differences:

- **Local Docker**: Uses your source code directly
- **GitHub Actions**: Checks out code fresh each time
- **Both**: Use same Android SDK, build tools, and Gradle optimizations

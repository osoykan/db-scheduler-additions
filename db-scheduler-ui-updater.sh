#!/bin/bash

# Configuration
REPO_URL="https://github.com/bekk/db-scheduler-ui.git"
BRANCH="main"
TEMP_DIR="temp-db-scheduler-ui"
SOURCE_DIR="$TEMP_DIR/db-scheduler-ui-frontend"
TARGET_DIR="db-scheduler-ui-ktor/src/main/resources/static/scheduler-ui"
SCRIPT_NAME=$(basename "$0")

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Logging functions
log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

log_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Check if we're in a git repository (optional for this simplified approach)
check_git_repo() {
    if ! git rev-parse --git-dir > /dev/null 2>&1; then
        log_warning "Not in a git repository. Changes won't be staged automatically."
        return 1
    fi
    return 0
}

# Check if git and required tools are available
check_dependencies() {
    if ! command -v git >/dev/null 2>&1; then
        log_error "git is required but not installed."
        exit 1
    fi
}

# Clean up temporary directory
cleanup_temp() {
    if [ -d "$TEMP_DIR" ]; then
        log_info "Cleaning up temporary directory..."
        rm -rf "$TEMP_DIR"
    fi
}

# Clone the repository to temporary directory
clone_repo() {
    log_info "Cloning db-scheduler-ui repository..."
    
    # Clean up any existing temp directory
    cleanup_temp
    
    # Try shallow clone first, fallback to full clone if it fails
    if git clone --depth 1 --branch "$BRANCH" "$REPO_URL" "$TEMP_DIR" 2>/dev/null; then
        log_success "Repository cloned successfully (shallow)"
    elif git clone --branch "$BRANCH" "$REPO_URL" "$TEMP_DIR"; then
        log_success "Repository cloned successfully (full)"
    else
        log_error "Failed to clone repository"
        return 1
    fi
    
    # Remove .git directory to avoid git history
    if [ -d "$TEMP_DIR/.git" ]; then
        rm -rf "$TEMP_DIR/.git"
        log_info "Removed .git directory"
    fi
    
    return 0
}

# Extract frontend directory
extract_frontend() {
    log_info "Extracting frontend directory..."

    if [ ! -d "$SOURCE_DIR" ]; then
        log_error "Source directory $SOURCE_DIR not found"
        return 1
    fi

    # Remove existing target directory
    if [ -d "$TARGET_DIR" ]; then
        log_info "Removing existing target directory..."
        rm -rf "$TARGET_DIR"
    fi

    # Create target directory
    mkdir -p "$TARGET_DIR"

    # Enable dotglob to include hidden files
    shopt -s dotglob

    # Copy frontend files (including dotfiles)
    local copy_success=true
    if [ "$(ls -A "$SOURCE_DIR" 2>/dev/null)" ]; then
        if ! cp -r "$SOURCE_DIR"/* "$TARGET_DIR"/ 2>/dev/null; then
            copy_success=false
        fi
    else
        log_warning "Source directory is empty"
    fi

    # Disable dotglob
    shopt -u dotglob

    if [ "$copy_success" = true ]; then
        log_success "Frontend files (including dotfiles) extracted to $TARGET_DIR"
        return 0
    else
        log_error "Failed to extract frontend files"
        return 1
    fi
}

# Clean up unnecessary files (optional)
cleanup_files() {
    log_info "Cleaning up unnecessary files..."

    # Remove common development files that aren't needed in production
    local cleanup_patterns=(
        "*.md"
        "*.MD"
        ".gitignore"
        ".git*"
        "package-lock.json"
        "yarn.lock"
        "node_modules"
        ".env*"
        "*.log"
    )

    for pattern in "${cleanup_patterns[@]}"; do
        find "$TARGET_DIR" -name "$pattern" -type f -delete 2>/dev/null || true
        find "$TARGET_DIR" -name "$pattern" -type d -exec rm -rf {} + 2>/dev/null || true
    done

    log_success "Cleanup completed"
}

# Stage changes for commit (if in git repo)
stage_changes() {
    if check_git_repo; then
        log_info "Staging changes..."
        if git add "$TARGET_DIR"; then
            log_success "Changes staged"
            return 0
        else
            log_error "Failed to stage changes"
            return 1
        fi
    else
        log_info "Not in git repository - skipping staging"
        return 0
    fi
}

# Show summary
show_summary() {
    echo
    log_info "Summary:"
    echo "  Repository: $REPO_URL"
    echo "  Branch: $BRANCH"
    echo "  Source: $SOURCE_DIR"
    echo "  Target: $TARGET_DIR"
    echo

    if [ -d "$TARGET_DIR" ]; then
        local file_count=$(find "$TARGET_DIR" -type f | wc -l)
        local dir_size=$(du -sh "$TARGET_DIR" 2>/dev/null | cut -f1)
        echo "  Files extracted: $file_count"
        echo "  Total size: $dir_size"
    fi

    echo
    log_success "Update completed! You can now commit the changes:"
    log_info "git commit -m \"Update db-scheduler UI frontend\""
}

# Main execution
main() {
    echo "========================================="
    echo "  DB Scheduler UI Frontend Updater"
    echo "========================================="
    echo

    # Set up cleanup trap
    trap cleanup_temp EXIT

    # Pre-flight checks
    check_dependencies
    check_git_repo  # Just for warning, not required

    # Clone repository to temporary directory
    if ! clone_repo; then
        exit 1
    fi

    # Extract frontend files
    if ! extract_frontend; then
        exit 1
    fi

    # Optional cleanup of development files
    cleanup_files

    # Stage changes (if in git repo)
    if ! stage_changes; then
        exit 1
    fi

    # Clean up temporary directory
    cleanup_temp

    # Show summary
    show_summary
}

# Help function
show_help() {
    echo "Usage: $SCRIPT_NAME [OPTIONS]"
    echo
    echo "Downloads and updates the db-scheduler-ui frontend components"
    echo "Uses a simple clone approach - no git history or merge commits"
    echo
    echo "Options:"
    echo "  -h, --help     Show this help message"
    echo "  --dry-run      Show what would be done without making changes"
    echo
    echo "Configuration (edit script to modify):"
    echo "  Repository: $REPO_URL"
    echo "  Branch: $BRANCH"
    echo "  Target: $TARGET_DIR"
    echo
    echo "The script will:"
    echo "  1. Clone the repository to a temporary directory"
    echo "  2. Copy frontend files to the target location"
    echo "  3. Remove .git directory and cleanup development files"
    echo "  4. Stage changes (if in a git repository)"
}

# Parse command line arguments
case "${1:-}" in
    -h|--help)
        show_help
        exit 0
        ;;
    --dry-run)
        log_info "DRY RUN MODE - No changes will be made"
        log_info "Would clone: $REPO_URL ($BRANCH)"
        log_info "Would update: $TARGET_DIR"
        log_info "Would use temp directory: $TEMP_DIR"
        exit 0
        ;;
    *)
        main "$@"
        ;;
esac

package ru.main.explorer;

/**
 * Adaptive cache budgets: downloaded map data must stay decoded instead of evicting visible cells
 * on every pan.
 */
final class CacheBudget {
    // Decoded vector geometry is the bulk of a dense downloaded region; give it a large heap share
    // so already-drawn cells are never re-decoded in a loop.
    static int geometry() {
        long heap = Runtime.getRuntime().maxMemory();
        return (int) Math.max(32L * 1024 * 1024, Math.min(heap / 12, 192L * 1024 * 1024));
    }

    // Raster imagery (satellite tiles, photo thumbnails, raster map tiles) stays bounded below the
    // geometry share.
    static int imagery() {
        long heap = Runtime.getRuntime().maxMemory();
        return (int) Math.max(24L * 1024 * 1024, Math.min(heap / 16, 128L * 1024 * 1024));
    }
}

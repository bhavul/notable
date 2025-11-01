package com.ethran.notable.editor.utils

import android.graphics.Path
import android.graphics.RectF
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.db.Image
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.StrokePoint
import com.ethran.notable.data.model.SimplePointF
import com.ethran.notable.editor.PageView
import com.ethran.notable.editor.state.EditorState
import io.shipbook.shipbooksdk.Log
import kotlinx.coroutines.CoroutineScope
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// Constants for lasso detection
const val LASSO_CLOSURE_THRESHOLD = 50f // Maximum distance between start and end points to consider loop closed
const val MINIMUM_LASSO_POINTS = 10 // Minimum number of points for a valid lasso
const val MINIMUM_LASSO_DIMENSION = 30f // Minimum width or height of bounding box

/**
 * Checks if a stroke forms a closed loop suitable for lasso selection.
 *
 * A stroke is considered a closed loop if:
 * 1. It has enough points (>= MINIMUM_LASSO_POINTS)
 * 2. Start and end points are close together (< LASSO_CLOSURE_THRESHOLD)
 * 3. The bounding box is large enough (both dimensions > MINIMUM_LASSO_DIMENSION)
 *
 * @param points List of stroke points in page coordinates
 * @return true if the stroke forms a valid closed loop
 */
fun isClosedLoop(points: List<StrokePoint>): Boolean {
    if (points.size < MINIMUM_LASSO_POINTS) {
        Log.d("SmartLasso", "Not enough points: ${points.size} < $MINIMUM_LASSO_POINTS")
        return false
    }

    // Check if start and end points are close together
    val firstPoint = points.first()
    val lastPoint = points.last()
    val dx = lastPoint.x - firstPoint.x
    val dy = lastPoint.y - firstPoint.y
    val distance = sqrt(dx * dx + dy * dy)

    if (distance > LASSO_CLOSURE_THRESHOLD) {
        Log.d("SmartLasso", "Loop not closed: distance $distance > $LASSO_CLOSURE_THRESHOLD")
        return false
    }

    // Check bounding box size to ensure it's not a tiny shape
    val boundingBox = calculateBoundingBox(points) { Pair(it.x, it.y) }
    val width = boundingBox.width()
    val height = boundingBox.height()

    if (width < MINIMUM_LASSO_DIMENSION || height < MINIMUM_LASSO_DIMENSION) {
        Log.d("SmartLasso", "Lasso too small: ${width}x$height < $MINIMUM_LASSO_DIMENSION")
        return false
    }

    Log.d("SmartLasso", "Valid closed loop detected: ${points.size} points, distance: $distance, size: ${width}x$height")
    return true
}

/**
 * Data class to hold the result of a lasso selection attempt.
 *
 * @param selectedStrokes List of strokes that were selected inside the lasso
 * @param selectedImages List of images that were selected inside the lasso
 * @param lassoPath The path of the lasso stroke itself
 * @param boundingBox The bounding box of the lasso
 */
data class LassoSelectionResult(
    val selectedStrokes: List<Stroke>,
    val selectedImages: List<Image>,
    val lassoPath: Path,
    val boundingBox: RectF
)

/**
 * Attempts to perform a lasso selection on the given page.
 *
 * If the stroke forms a closed loop and Smart Lasso is enabled, this function
 * will select all strokes and images inside the loop.
 *
 * @param page The page containing strokes and images to select from
 * @param touchPoints The points of the lasso stroke in page coordinates
 * @return LassoSelectionResult if selection was successful, null otherwise
 */
fun tryLassoSelection(
    page: PageView,
    touchPoints: List<StrokePoint>
): LassoSelectionResult? {
    // Check if Smart Lasso is enabled
    if (!GlobalAppSettings.current.smartLassoEnabled) {
        Log.d("SmartLasso", "Smart Lasso is disabled")
        return null
    }

    // Check if the stroke forms a closed loop
    if (!isClosedLoop(touchPoints)) {
        return null
    }

    // Create a path from the lasso points and close it
    val lassoPoints = touchPoints.map { SimplePointF(it.x, it.y) }
    val lassoPath = pointsToPath(lassoPoints)
    lassoPath.close() // Ensure the path is closed

    // Calculate bounding box
    val boundingBox = RectF()
    lassoPath.computeBounds(boundingBox, true)

    // Select strokes and images inside the lasso
    val selectedStrokes = selectStrokesFromPath(page.strokes, lassoPath)
    val selectedImages = selectImagesFromPath(page.images, lassoPath)

    // Only return a result if something was selected
    if (selectedStrokes.isEmpty() && selectedImages.isEmpty()) {
        Log.d("SmartLasso", "No strokes or images found inside lasso")
        return null
    }

    Log.d("SmartLasso", "Lasso selection successful: ${selectedStrokes.size} strokes, ${selectedImages.size} images")
    return LassoSelectionResult(selectedStrokes, selectedImages, lassoPath, boundingBox)
}

/**
 * Handles smart lasso selection during drawing.
 *
 * This is the main entry point called from DrawCanvas when a stroke is completed.
 * If the stroke forms a closed loop, it will:
 * 1. Select strokes/images inside the loop
 * 2. Set up the selection state
 * 3. Store the lasso stroke for potential later drawing
 * 4. Return true to indicate the stroke should NOT be drawn yet
 *
 * If the stroke is not a lasso or nothing is selected, returns false to indicate
 * the stroke should be drawn normally.
 *
 * @param scope Coroutine scope for async operations
 * @param page The page to select from
 * @param editorState The editor state containing selection state
 * @param touchPoints The lasso stroke points in page coordinates
 * @param strokeSize The size of the lasso stroke (for potential later drawing)
 * @param color The color of the lasso stroke (for potential later drawing)
 * @param pen The pen type used for the lasso stroke
 * @return true if lasso selection was performed, false if stroke should be drawn normally
 */
fun handleSmartLasso(
    scope: CoroutineScope,
    page: PageView,
    editorState: EditorState,
    touchPoints: List<StrokePoint>,
    strokeSize: Float,
    color: Int,
    pen: Pen
): Boolean {
    val result = tryLassoSelection(page, touchPoints) ?: return false

    // Store the lasso stroke data for potential later drawing if user cancels selection
    editorState.selectionState.pendingLassoStroke = touchPoints
    editorState.selectionState.pendingLassoStrokeSize = strokeSize
    editorState.selectionState.pendingLassoColor = color
    editorState.selectionState.pendingLassoPen = pen

    // Perform the selection
    selectImagesAndStrokes(
        scope,
        page,
        editorState,
        result.selectedImages,
        result.selectedStrokes
    )

    Log.d("SmartLasso", "Smart Lasso selection applied, pending stroke stored")
    return true
}

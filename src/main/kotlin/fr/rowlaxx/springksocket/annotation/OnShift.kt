package fr.rowlaxx.springksocket.annotation

/**
 * Marks a method to be called whenever the [PerpetualWebSocket][fr.rowlaxx.springksocket.model.PerpetualWebSocket]
 * shifts to a new underlying connection.
 *
 * The annotated method must declare two [WebSocket?][fr.rowlaxx.springksocket.model.WebSocket] parameters,
 * passed positionally:
 * - the previous connection (null when there was none, e.g. the very first connection)
 * - the new connection (null when the shift leaves no connection)
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class OnShift()

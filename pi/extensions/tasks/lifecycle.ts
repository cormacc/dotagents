import {
  appendStateLog,
  formatOrgTimestamp,
  taskHasStartedProperty,
  type Task,
} from "./parser.ts";

export const CLOSED_STATUSES = new Set<string>(["DONE", "CANCELLED"]);

export interface StatusTransitionResult {
  prevStatus: string;
  status: string;
  wasClosed: boolean;
  isClosed: boolean;
  timestamp: string;
}

/**
 * Apply the org-memory lifecycle semantics for a single status change.
 *
 * This is the pure core used by the overlay and regression tests:
 * - append one LOGBOOK state entry per live transition
 * - stamp CLOSED on entry into a terminal state
 * - clear CLOSED on reopen from a terminal state
 * - preserve the first :STARTED: timestamp across later reopens
 */
export function applyStatusTransition(
  task: Task,
  status: string,
  timestamp: string = formatOrgTimestamp(),
): StatusTransitionResult {
  const prevStatus = task.status;
  task.status = status;
  appendStateLog(task, status, prevStatus, timestamp);

  const wasClosed = CLOSED_STATUSES.has(prevStatus);
  const isClosed = CLOSED_STATUSES.has(status);

  if (isClosed) {
    if (!task.closed) task.closed = timestamp;
  } else if (wasClosed) {
    task.closed = null;
  }

  if (status === "STARTED" && !taskHasStartedProperty(task)) {
    task.propertyLines.push(`:STARTED: [${timestamp}]`);
  }

  return { prevStatus, status, wasClosed, isClosed, timestamp };
}

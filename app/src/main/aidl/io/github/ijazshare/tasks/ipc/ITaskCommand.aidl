// io/github/ijazshare/tasks/ipc/ITaskCommand.aidl
package io.github.ijazshare.tasks.ipc;
interface ITaskCommand {
    /** Highest contract major version this build speaks, e.g. 1. */
    int contractVersion();
    /** Request JSON (section 4.4) in, response JSON (4.5) out. Never throws for bad input. */
    String handle(String requestJson);
}

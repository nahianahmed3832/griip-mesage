/**
 * Runs async sections one at a time in this process: a check and the reservation that depends on it stay together
 * even when the check has to wait for a database (files quotas, src/modules/files/routes.ts).
 */
export class AsyncLock {
  private tail: Promise<unknown> = Promise.resolve();

  run<T>(fn: () => Promise<T>): Promise<T> {
    const run = this.tail.then(fn, fn);
    this.tail = run.catch(() => undefined);
    return run;
  }
}

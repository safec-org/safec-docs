# thread -- Threading

The `thread` module provides cross-platform threading primitives: threads, mutexes, condition variables, and read-write locks. It uses POSIX pthreads on Linux/macOS and Win32 threads on Windows (compile with `-D__WINDOWS__` to select the Win32 backend).

```c
#include "thread.h"
```

## Handle Representation

All handles (thread IDs, mutexes, condition variables, read-write locks) are stored as `unsigned long long`:

- **POSIX**: `pthread_t` value (8 bytes on 64-bit), or a pointer to a heap-allocated pthread object
- **Win32**: `HANDLE` (kernel object pointer, 8 bytes on x64), or a pointer to a heap-allocated Windows primitive

## Thread

```c
int  thread_create(unsigned long long* tid, void* fn, void* arg);
int  thread_join(unsigned long long tid);
int  thread_detach(unsigned long long tid);
void thread_yield();
void thread_sleep_ms(unsigned long ms);
unsigned long long thread_self();
```

### thread_create

Create a new thread. The function signature must be `void* fn(void*)` on POSIX or `DWORD WINAPI fn(LPVOID)` on Win32. The thread ID is written to `*tid`. Returns 0 on success.

### thread_join

Block until thread `tid` finishes. Returns 0 on success.

### thread_detach

Detach thread `tid` so its resources are automatically reclaimed on exit. Returns 0 on success.

### thread_yield

Yield the current thread's time slice to other runnable threads.

### thread_sleep_ms

Sleep the calling thread for `ms` milliseconds.

### thread_self

Return the calling thread's ID.

## Mutex

```c
int mutex_init(unsigned long long* m);
int mutex_destroy(unsigned long long* m);
int mutex_lock(unsigned long long* m);
int mutex_trylock(unsigned long long* m);
int mutex_unlock(unsigned long long* m);
```

### mutex_init / mutex_destroy

Initialize or destroy a mutex. The mutex handle is stored in `*m`.

### mutex_lock / mutex_unlock

Acquire or release the mutex. `mutex_lock` blocks if the mutex is held by another thread.

### mutex_trylock

Attempt to acquire the mutex without blocking. Returns 0 if the lock was acquired, non-zero if busy.

## Condition Variable

```c
int cond_init(unsigned long long* cv);
int cond_destroy(unsigned long long* cv);
int cond_wait(unsigned long long* cv, unsigned long long* m);
int cond_timedwait_ms(unsigned long long* cv, unsigned long long* m, unsigned long ms);
int cond_signal(unsigned long long* cv);
int cond_broadcast(unsigned long long* cv);
```

### cond_wait

Atomically release mutex `*m` and block until the condition variable is signaled. Re-acquires the mutex before returning.

### cond_timedwait_ms

Like `cond_wait`, but with a timeout in milliseconds. Returns non-zero on timeout.

### cond_signal / cond_broadcast

Wake one (`signal`) or all (`broadcast`) threads waiting on the condition variable.

## Read-Write Lock

```c
int rwlock_init(unsigned long long* rw);
int rwlock_destroy(unsigned long long* rw);
int rwlock_rdlock(unsigned long long* rw);
int rwlock_wrlock(unsigned long long* rw);
int rwlock_tryrdlock(unsigned long long* rw);
int rwlock_trywrlock(unsigned long long* rw);
int rwlock_rdunlock(unsigned long long* rw);
int rwlock_wrunlock(unsigned long long* rw);
```

Multiple simultaneous readers are allowed; writers are exclusive. Use `rdlock`/`rdunlock` for shared read access, and `wrlock`/`wrunlock` for exclusive write access.

## Example

```c
#include "thread.h"
#include "io.h"
#include "atomic.h"

int counter = 0;
unsigned long long mtx;

void* worker(void* arg) {
    int i = 0;
    while (i < 1000) {
        mutex_lock(&mtx);
        counter = counter + 1;
        mutex_unlock(&mtx);
        i = i + 1;
    }
    return 0;
}

int main() {
    mutex_init(&mtx);

    unsigned long long t1;
    unsigned long long t2;
    thread_create(&t1, worker, 0);
    thread_create(&t2, worker, 0);

    thread_join(t1);
    thread_join(t2);

    print("counter = ");
    println_int(counter);  // 2000

    mutex_destroy(&mtx);
    return 0;
}
```

## Producer-Consumer Example

```c
#include "thread.h"
#include "io.h"

int buffer = 0;
int ready  = 0;
unsigned long long mtx;
unsigned long long cv;

void* producer(void* arg) {
    mutex_lock(&mtx);
    buffer = 42;
    ready  = 1;
    cond_signal(&cv);
    mutex_unlock(&mtx);
    return 0;
}

void* consumer(void* arg) {
    mutex_lock(&mtx);
    while (ready == 0) {
        cond_wait(&cv, &mtx);
    }
    print("received: ");
    println_int(buffer);  // 42
    mutex_unlock(&mtx);
    return 0;
}

int main() {
    mutex_init(&mtx);
    cond_init(&cv);

    unsigned long long t1;
    unsigned long long t2;
    thread_create(&t1, consumer, 0);
    thread_create(&t2, producer, 0);

    thread_join(t1);
    thread_join(t2);

    cond_destroy(&cv);
    mutex_destroy(&mtx);
    return 0;
}
```

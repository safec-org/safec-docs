/* Link-time stand-ins for the Objective-C / Metal entry points that
   std/ml/gpu_mps.sc references, so the project links on Linux. There is no
   Metal device: MTLCreateSystemDefaultDevice returns null, so
   mps_available() is 0 and every MPS path falls back. */
#include <stddef.h>
void* MTLCreateSystemDefaultDevice(void) { return NULL; }
void* objc_getClass(const char* name) { (void)name; return NULL; }
void* sel_registerName(const char* name) { (void)name; return NULL; }
void* objc_msgSend(void* self, void* sel, ...) { (void)self; (void)sel; return NULL; }
void* dispatch_data_create(const void* buf, size_t n, void* queue, void* destructor) {
    (void)buf; (void)n; (void)queue; (void)destructor; return NULL;
}

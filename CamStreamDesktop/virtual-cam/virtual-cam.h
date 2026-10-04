#pragma once
#include <windows.h>
#include <objbase.h>
#include <combaseapi.h>
#include <dshow.h>
#include <strmif.h>
#include <cstdint>

#pragma pack(push, 4)
// Shared frame structure matches the 32-byte header written by virtual-cam-writer.js.
struct SharedFrame {
    DWORD magic;
    DWORD width;
    DWORD height;
    DWORD stride;
    DWORD cameraState;
    DWORD64 timestamp;
    DWORD dataSize;
    BYTE  data[1];
};
#pragma pack(pop)

#define SHARED_FRAME_MAGIC 0x434D5354
#define SHARED_FRAME_WIDTH 1280
#define SHARED_FRAME_HEIGHT 720
#define SHARED_FRAME_HEADER_SIZE ((DWORD)offsetof(SharedFrame, data))
#define SHARED_MAX_RGB_SIZE (1920 * 1080 * 3)
#define SHARED_FRAME_SIZE (SHARED_FRAME_HEADER_SIZE + SHARED_MAX_RGB_SIZE + 4)
inline DWORD SharedFrameFps(const SharedFrame* frame) {
    DWORD fps = *(const volatile DWORD*)(frame->data + SHARED_MAX_RGB_SIZE);
    return fps == 60 ? 60 : 30;
}
#define SHARED_CAMERA_STATE_IDLE 0
#define SHARED_CAMERA_STATE_CONNECTED 1
static_assert(offsetof(SharedFrame, timestamp) == 20, "Shared memory timestamp offset mismatch");
static_assert(offsetof(SharedFrame, dataSize) == 28, "Shared memory dataSize offset mismatch");
static_assert(offsetof(SharedFrame, data) == 32, "Shared memory pixel data offset mismatch");

// Shared memory name for inter-process frame exchange
#define SHARED_MEM_NAME L"Local\\CamStreamVirtualCam_Frame"

#define FILTER_FRIENDLY_NAME L"JyroCam"
#define FILTER_PIN_NAME L"Capture"

// Forward declaration of CLSID (defined in shared_memory.cpp)
extern "C" const GUID CLSID_CamStreamVirtualCam;

// Forward declarations of shared memory functions (defined in shared_memory.cpp)
extern "C" {
    SharedFrame* SharedMemory_Open();
    void SharedMemory_Close(SharedFrame* frame);
    BOOL SharedMemory_ReadFrame(SharedFrame* frame, BYTE* dest, DWORD destSize,
                                DWORD* outWidth, DWORD* outHeight, DWORD64* outTimestamp);
}

// DirectShow helper functions (defined in output_pin.cpp)
HRESULT CopyMediaType(AM_MEDIA_TYPE* pDest, const AM_MEDIA_TYPE* pSrc);
void FreeMediaType(AM_MEDIA_TYPE& mt);

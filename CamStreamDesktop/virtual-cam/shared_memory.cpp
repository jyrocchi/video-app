#include "virtual-cam.h"
#include <stdio.h>

extern "C" const GUID CLSID_CamStreamVirtualCam = {
    0xB4E5B3A0, 0x1B0C, 0x4F8E, { 0x9D, 0x4D, 0x8C, 0x5E, 0x7F, 0x3A, 0x2B, 0x1D }
};

// Define IID_IMemOutputPin since it's not in the current SDK headers
extern "C" const GUID IID_IMemOutputPin_Manual = {
    0x6C711001, 0x5731, 0x11D0, { 0x83, 0x84, 0x00, 0xA0, 0xC9, 0x11, 0xB8, 0x49 }
};

extern "C" const GUID IID_IMemInputPin_Manual = {
    0x56A8689D, 0x0AD4, 0x11CE, { 0xB0, 0x3A, 0x00, 0x20, 0xAF, 0x0B, 0xA7, 0x70 }
};

extern "C" SharedFrame* SharedMemory_Open() {
    HANDLE hMap = OpenFileMappingW(FILE_MAP_ALL_ACCESS, FALSE, SHARED_MEM_NAME);
    if (!hMap) {
        hMap = CreateFileMappingW(INVALID_HANDLE_VALUE, NULL, PAGE_READWRITE,
                                   0, SHARED_FRAME_SIZE, SHARED_MEM_NAME);
        if (!hMap) return NULL;
    }
    void* ptr = MapViewOfFile(hMap, FILE_MAP_ALL_ACCESS, 0, 0, SHARED_FRAME_SIZE);
    CloseHandle(hMap);
    if (!ptr) return NULL;
    SharedFrame* frame = (SharedFrame*)ptr;
    if (frame->magic != SHARED_FRAME_MAGIC) {
        frame->magic = SHARED_FRAME_MAGIC;
        frame->width = SHARED_FRAME_WIDTH;
        frame->height = SHARED_FRAME_HEIGHT;
        frame->stride = SHARED_FRAME_WIDTH * 3;
        frame->format = 0;
        frame->timestamp = 0;
        frame->dataSize = SHARED_FRAME_WIDTH * SHARED_FRAME_HEIGHT * 3;
        ZeroMemory(frame->data, frame->dataSize);
    }
    return frame;
}

extern "C" void SharedMemory_Close(SharedFrame* frame) {
    if (frame) UnmapViewOfFile(frame);
}

extern "C" BOOL SharedMemory_ReadFrame(SharedFrame* frame, BYTE* dest, DWORD destSize,
                                        DWORD* outWidth, DWORD* outHeight, DWORD64* outTimestamp) {
    if (!frame) return FALSE;
    if (frame->magic != SHARED_FRAME_MAGIC) return FALSE;
    if (frame->width != SHARED_FRAME_WIDTH || frame->height != SHARED_FRAME_HEIGHT ||
        frame->stride != SHARED_FRAME_WIDTH * 3 ||
        frame->dataSize != SHARED_FRAME_WIDTH * SHARED_FRAME_HEIGHT * 3 ||
        frame->dataSize > destSize) return FALSE;
    memcpy(dest, frame->data, frame->dataSize);
    if (outWidth) *outWidth = frame->width;
    if (outHeight) *outHeight = frame->height;
    if (outTimestamp) *outTimestamp = frame->timestamp;
    return TRUE;
}

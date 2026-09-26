#include <windows.h>
#include <dshow.h>
#include <stdio.h>
#include "virtual-cam.h"

int wmain() {
    HRESULT hr = CoInitializeEx(NULL, COINIT_MULTITHREADED);
    if (FAILED(hr) && hr != RPC_E_CHANGED_MODE) {
        wprintf(L"CoInitializeEx failed: 0x%08X\n", (unsigned)hr);
        return 1;
    }

    IBaseFilter* filter = NULL;
    hr = CoCreateInstance(CLSID_CamStreamVirtualCam, NULL, CLSCTX_INPROC_SERVER,
                          IID_IBaseFilter, (void**)&filter);
    if (FAILED(hr)) {
        wprintf(L"CoCreateInstance failed: 0x%08X\n", (unsigned)hr);
        CoUninitialize();
        return 2;
    }
    filter->Release();
    filter = NULL;

    ICreateDevEnum* deviceEnum = NULL;
    hr = CoCreateInstance(CLSID_SystemDeviceEnum, NULL, CLSCTX_INPROC_SERVER,
                          IID_ICreateDevEnum, (void**)&deviceEnum);
    if (FAILED(hr)) { wprintf(L"SystemDeviceEnum failed: 0x%08X\n", (unsigned)hr); return 5; }
    IEnumMoniker* monikers = NULL;
    hr = deviceEnum->CreateClassEnumerator(CLSID_VideoInputDeviceCategory, &monikers, 0);
    if (FAILED(hr) || !monikers) { wprintf(L"Video input category empty\n"); return 6; }
    IMoniker* moniker = NULL;
    ULONG monikerFetched = 0;
    bool bound = false;
    while (monikers->Next(1, &moniker, &monikerFetched) == S_OK && monikerFetched == 1) {
        IPropertyBag* bag = NULL;
        VARIANT value;
        VariantInit(&value);
        if (SUCCEEDED(moniker->BindToStorage(NULL, NULL, IID_IPropertyBag, (void**)&bag)) &&
            SUCCEEDED(bag->Read(L"FriendlyName", &value, NULL)) && value.vt == VT_BSTR &&
            wcscmp(value.bstrVal, L"CamStream Virtual Camera") == 0) {
            IBaseFilter* enumeratedFilter = NULL;
            HRESULT bindHr = moniker->BindToObject(NULL, NULL, IID_IBaseFilter, (void**)&enumeratedFilter);
            wprintf(L"Moniker BindToObject HRESULT=0x%08X filter=%p\n", (unsigned)bindHr, enumeratedFilter);
            bound = SUCCEEDED(bindHr);
            if (enumeratedFilter) enumeratedFilter->Release();
        }
        VariantClear(&value);
        if (bag) bag->Release();
        moniker->Release();
        moniker = NULL;
    }
    monikers->Release();
    deviceEnum->Release();
    if (!bound) { wprintf(L"Could not bind enumerated moniker\n"); CoUninitialize(); return 7; }

    filter = NULL;
    hr = CoCreateInstance(CLSID_CamStreamVirtualCam, NULL, CLSCTX_INPROC_SERVER,
                          IID_IBaseFilter, (void**)&filter);
    if (FAILED(hr)) { wprintf(L"Direct CoCreateInstance failed: 0x%08X\n", (unsigned)hr); return 8; }

    IEnumPins* pins = NULL;
    hr = filter->EnumPins(&pins);
    IPin* pin = NULL;
    ULONG fetched = 0;
    if (SUCCEEDED(hr)) hr = pins->Next(1, &pin, &fetched);
    if (FAILED(hr) || fetched != 1 || !pin) {
        wprintf(L"Output pin unavailable: 0x%08X\n", (unsigned)hr);
        if (pins) pins->Release();
        filter->Release();
        CoUninitialize();
        return 3;
    }

    IEnumMediaTypes* types = NULL;
    hr = pin->EnumMediaTypes(&types);
    AM_MEDIA_TYPE* type = NULL;
    fetched = 0;
    if (SUCCEEDED(hr)) hr = types->Next(1, &type, &fetched);
    if (FAILED(hr) || fetched != 1 || !type) {
        wprintf(L"Media type unavailable: 0x%08X\n", (unsigned)hr);
        if (types) types->Release();
        pin->Release(); pins->Release(); filter->Release(); CoUninitialize();
        return 4;
    }

    VIDEOINFOHEADER* vih = (VIDEOINFOHEADER*)type->pbFormat;
    wprintf(L"CamStream Virtual Camera OK: %ldx%ld, %lu-bit RGB24\n",
            vih->bmiHeader.biWidth, labs(vih->bmiHeader.biHeight),
            vih->bmiHeader.biBitCount);
    CoTaskMemFree(type->pbFormat);
    CoTaskMemFree(type);
    types->Release(); pin->Release(); pins->Release(); filter->Release();
    CoUninitialize();
    return 0;
}

#include <windows.h>
#include <objbase.h>
#include <combaseapi.h>
#ifndef CLASS_E_CLASSNOTREG
#define CLASS_E_CLASSNOTREG 0x80040154
#endif
#include "virtual-cam.h"
#include "filter.h"

HINSTANCE g_hInstance = NULL;
LONG g_cLocks = 0;

BOOL APIENTRY DllMain(HMODULE hModule, DWORD reason, LPVOID) {
    if (reason == DLL_PROCESS_ATTACH) {
        g_hInstance = (HINSTANCE)hModule;
        DisableThreadLibraryCalls(hModule);
    }
    return TRUE;
}

STDAPI DllCanUnloadNow() {
    return g_cLocks == 0 ? S_OK : S_FALSE;
}

class CClassFactory : public IClassFactory {
public:
    CClassFactory() : m_cRef(1) {}
    STDMETHODIMP QueryInterface(REFIID riid, void** ppv) override {
        if (riid == IID_IUnknown || riid == IID_IClassFactory) {
            *ppv = (IClassFactory*)this;
            AddRef();
            return S_OK;
        }
        return *ppv = NULL, E_NOINTERFACE;
    }
    STDMETHODIMP_(ULONG) AddRef() override { return InterlockedIncrement(&m_cRef); }
    STDMETHODIMP_(ULONG) Release() override {
        LONG c = InterlockedDecrement(&m_cRef);
        if (c == 0) delete this;
        return c;
    }
    STDMETHODIMP CreateInstance(IUnknown* pUnkOuter, REFIID riid, void** ppv) override {
        if (pUnkOuter) return CLASS_E_NOAGGREGATION;
        CFilter* pFilter = NULL;
        HRESULT hr = CFilter::CreateInstance(&pFilter);
        if (FAILED(hr)) return hr;
        hr = pFilter->QueryInterface(riid, ppv);
        pFilter->Release();
        return hr;
    }
    STDMETHODIMP LockServer(BOOL fLock) override {
        if (fLock) InterlockedIncrement(&g_cLocks);
        else InterlockedDecrement(&g_cLocks);
        return S_OK;
    }
private:
    LONG m_cRef;
};

STDAPI DllGetClassObject(REFCLSID rclsid, REFIID riid, void** ppv) {
    if (!IsEqualCLSID(rclsid, CLSID_CamStreamVirtualCam)) return CLASS_E_CLASSNOTREG;
    CClassFactory* pFactory = new CClassFactory();
    if (!pFactory) return E_OUTOFMEMORY;
    HRESULT hr = pFactory->QueryInterface(riid, ppv);
    pFactory->Release();
    return hr;
}

static const LPWSTR FILTER_NAME = FILTER_FRIENDLY_NAME;
static const WCHAR CLSID_STR[] = L"{B4E5B3A0-1B0C-4F8E-9D4D-8C5E7F3A2B1D}";

static void FormatClsidKey(WCHAR* dest, size_t destSize, const WCHAR* suffix) {
    if (!suffix) {
        wcscpy_s(dest, destSize, L"Software\\Classes\\CLSID\\");
        wcscat_s(dest, destSize, CLSID_STR);
    } else {
        wcscpy_s(dest, destSize, L"Software\\Classes\\CLSID\\");
        wcscat_s(dest, destSize, CLSID_STR);
        wcscat_s(dest, destSize, L"\\");
        wcscat_s(dest, destSize, suffix);
    }
}

static HRESULT RegisterCategoryInstance(const WCHAR* category) {
    WCHAR subkey[512];
    wcscpy_s(subkey, _countof(subkey), category);
    wcscat_s(subkey, _countof(subkey), L"\\");
    wcscat_s(subkey, _countof(subkey), CLSID_STR);

    HKEY hKey = NULL;
    if (RegCreateKeyExW(HKEY_CURRENT_USER, subkey, 0, NULL, 0, KEY_WRITE,
                        NULL, &hKey, NULL) != ERROR_SUCCESS) return E_FAIL;
    LONG friendlyResult = RegSetValueExW(hKey, L"FriendlyName", 0, REG_SZ,
        (const BYTE*)FILTER_NAME, (DWORD)(wcslen(FILTER_NAME) + 1) * sizeof(WCHAR));
    LONG clsidResult = RegSetValueExW(hKey, L"CLSID", 0, REG_SZ,
        (const BYTE*)CLSID_STR, (DWORD)(wcslen(CLSID_STR) + 1) * sizeof(WCHAR));
    RegCloseKey(hKey);
    return friendlyResult == ERROR_SUCCESS && clsidResult == ERROR_SUCCESS ? S_OK : E_FAIL;
}

static void RemoveLegacyCategoryValues() {
    const WCHAR* categories[] = {
        L"Software\\Classes\\CLSID\\{860BB310-5D01-11d0-BD3B-00A0C911CE86}\\Instance",
        L"Software\\Classes\\CLSID\\{860BB310-5D01-11d0-BD3B-00A0C911CE86}\\Instance32",
        L"Software\\Classes\\CLSID\\{173FB317-30E9-4d83-9CDC-99373B40B7C0}\\Instance",
        L"Software\\Classes\\CLSID\\{173FB317-30E9-4d83-9CDC-99373B40B7C0}\\Instance32"
    };
    for (int i = 0; i < _countof(categories); i++) {
        HKEY hKey = NULL;
        if (RegOpenKeyExW(HKEY_CURRENT_USER, categories[i], 0, KEY_SET_VALUE, &hKey) == ERROR_SUCCESS) {
            RegDeleteValueW(hKey, L"CLSID");
            RegCloseKey(hKey);
        }
    }
}

static HRESULT RegisterDirectShowVideoInput() {
    REGPINTYPES mediaType = { &MEDIATYPE_Video, &MEDIASUBTYPE_RGB24 };
    REGFILTERPINS2 pin = {};
    pin.dwFlags = REG_PINFLAG_B_OUTPUT;
    pin.cInstances = 1;
    pin.nMediaTypes = 1;
    pin.lpMediaType = &mediaType;

    REGFILTER2 filter = {};
    filter.dwVersion = 2;
    filter.dwMerit = MERIT_DO_NOT_USE;
    filter.cPins2 = 1;
    filter.rgPins2 = &pin;

    IFilterMapper2* mapper = NULL;
    HRESULT hr = CoCreateInstance(CLSID_FilterMapper2, NULL, CLSCTX_INPROC_SERVER,
                                  IID_IFilterMapper2, (void**)&mapper);
    if (FAILED(hr)) return hr;
    hr = mapper->RegisterFilter(CLSID_CamStreamVirtualCam, FILTER_NAME, NULL,
                                &CLSID_VideoInputDeviceCategory, FILTER_NAME, &filter);
    mapper->Release();
    return hr;
}

STDAPI DllRegisterServer() {
    HRESULT initHr = CoInitializeEx(NULL, COINIT_APARTMENTTHREADED);
    BOOL uninitialize = SUCCEEDED(initHr);
    if (FAILED(initHr) && initHr != RPC_E_CHANGED_MODE) return initHr;
    RemoveLegacyCategoryValues();

    HKEY hKey = NULL;
    WCHAR path[MAX_PATH];
    GetModuleFileNameW(g_hInstance, path, MAX_PATH);

    WCHAR subkey[256];
    FormatClsidKey(subkey, 256, NULL);

    if (RegCreateKeyExW(HKEY_CURRENT_USER, subkey, 0, NULL, 0,
                         KEY_WRITE, NULL, &hKey, NULL) != ERROR_SUCCESS)
        return E_FAIL;

    RegSetValueExW(hKey, NULL, 0, REG_SZ, (BYTE*)FILTER_NAME,
                   (DWORD)(wcslen(FILTER_NAME) + 1) * sizeof(WCHAR));
    RegCloseKey(hKey);

    FormatClsidKey(subkey, 256, L"InprocServer32");
    if (RegCreateKeyExW(HKEY_CURRENT_USER, subkey, 0, NULL, 0,
                         KEY_WRITE, NULL, &hKey, NULL) == ERROR_SUCCESS) {
        RegSetValueExW(hKey, NULL, 0, REG_SZ, (BYTE*)path,
                       (DWORD)(wcslen(path) + 1) * sizeof(WCHAR));
        RegSetValueExW(hKey, L"ThreadingModel", 0, REG_SZ, (BYTE*)L"Both", 10);
        RegCloseKey(hKey);
    }

    // RegisterFilter writes to the machine-wide category on many Windows
    // versions and can require elevation. The HKCU moniker registration below
    // remains usable for a per-user installation when that call is denied.
    RegisterDirectShowVideoInput();

    const WCHAR* categories[] = {
        L"Software\\Classes\\CLSID\\{860BB310-5D01-11d0-BD3B-00A0C911CE86}\\Instance"
    };
    for (int i = 0; i < _countof(categories); i++) {
        HRESULT hr = RegisterCategoryInstance(categories[i]);
        if (FAILED(hr)) {
            if (uninitialize) CoUninitialize();
            return hr;
        }
    }

    if (uninitialize) CoUninitialize();
    return S_OK;
}

STDAPI DllUnregisterServer() {
    HRESULT initHr = CoInitializeEx(NULL, COINIT_APARTMENTTHREADED);
    BOOL uninitialize = SUCCEEDED(initHr);
    if (FAILED(initHr) && initHr != RPC_E_CHANGED_MODE) return initHr;
    RemoveLegacyCategoryValues();
    IFilterMapper2* mapper = NULL;
    if (SUCCEEDED(CoCreateInstance(CLSID_FilterMapper2, NULL, CLSCTX_INPROC_SERVER,
                                   IID_IFilterMapper2, (void**)&mapper))) {
        mapper->UnregisterFilter(&CLSID_VideoInputDeviceCategory, FILTER_NAME,
                                 CLSID_CamStreamVirtualCam);
        mapper->Release();
    }
    const WCHAR* categories[] = {
        L"Software\\Classes\\CLSID\\{860BB310-5D01-11d0-BD3B-00A0C911CE86}\\Instance\\{B4E5B3A0-1B0C-4F8E-9D4D-8C5E7F3A2B1D}",
        L"Software\\Classes\\CLSID\\{860BB310-5D01-11d0-BD3B-00A0C911CE86}\\Instance32\\{B4E5B3A0-1B0C-4F8E-9D4D-8C5E7F3A2B1D}"
    };
    for (int i = 0; i < _countof(categories); i++)
        RegDeleteTreeW(HKEY_CURRENT_USER, categories[i]);
    RegDeleteTreeW(HKEY_CURRENT_USER, L"Software\\Classes\\CLSID\\{B4E5B3A0-1B0C-4F8E-9D4D-8C5E7F3A2B1D}");
    if (uninitialize) CoUninitialize();
    return S_OK;
}

#include "output_pin.h"
#include "filter.h"
#include "virtual-cam.h"
#include <dshow.h>
#include <vfw.h>

class CEnumMediaTypes : public IEnumMediaTypes {
public:
    explicit CEnumMediaTypes(const AM_MEDIA_TYPE& type, ULONG index = 0) : m_ref(1), m_index(index) {
        ZeroMemory(&m_type, sizeof(m_type));
        CopyMediaType(&m_type, &type);
    }
    ~CEnumMediaTypes() { FreeMediaType(m_type); }
    STDMETHODIMP QueryInterface(REFIID riid, void** ppv) override {
        if (!ppv) return E_POINTER;
        if (riid == IID_IUnknown || riid == IID_IEnumMediaTypes) { *ppv = this; AddRef(); return S_OK; }
        *ppv = NULL; return E_NOINTERFACE;
    }
    STDMETHODIMP_(ULONG) AddRef() override { return InterlockedIncrement(&m_ref); }
    STDMETHODIMP_(ULONG) Release() override { ULONG n = InterlockedDecrement(&m_ref); if (!n) delete this; return n; }
    STDMETHODIMP Next(ULONG count, AM_MEDIA_TYPE** types, ULONG* fetched) override {
        if (!types || (count != 1 && !fetched)) return E_POINTER;
        ULONG n = 0;
        if (m_index == 0 && count) {
            types[0] = (AM_MEDIA_TYPE*)CoTaskMemAlloc(sizeof(AM_MEDIA_TYPE));
            if (!types[0]) return E_OUTOFMEMORY;
            ZeroMemory(types[0], sizeof(AM_MEDIA_TYPE));
            HRESULT hr = CopyMediaType(types[0], &m_type);
            if (FAILED(hr)) { CoTaskMemFree(types[0]); types[0] = NULL; return hr; }
            ++m_index; n = 1;
        }
        if (fetched) *fetched = n;
        return n == count ? S_OK : S_FALSE;
    }
    STDMETHODIMP Skip(ULONG count) override {
        if (m_index == 0 && count) { m_index = 1; return count == 1 ? S_OK : S_FALSE; }
        return S_FALSE;
    }
    STDMETHODIMP Reset() override { m_index = 0; return S_OK; }
    STDMETHODIMP Clone(IEnumMediaTypes** result) override {
        if (!result) return E_POINTER;
        *result = new CEnumMediaTypes(m_type, m_index);
        return *result ? S_OK : E_OUTOFMEMORY;
    }
private:
    LONG m_ref;
    ULONG m_index;
    AM_MEDIA_TYPE m_type;
};

HRESULT CopyMediaType(AM_MEDIA_TYPE* pDest, const AM_MEDIA_TYPE* pSrc) {
    if (!pDest || !pSrc) return E_POINTER;
    *pDest = *pSrc;
    if (pSrc->pbFormat) {
        pDest->pbFormat = (BYTE*)CoTaskMemAlloc(pSrc->cbFormat);
        if (!pDest->pbFormat) return E_OUTOFMEMORY;
        CopyMemory(pDest->pbFormat, pSrc->pbFormat, pSrc->cbFormat);
    }
    if (pSrc->pUnk) {
        pDest->pUnk = pSrc->pUnk;
        pSrc->pUnk->AddRef();
    }
    return S_OK;
}

void FreeMediaType(AM_MEDIA_TYPE& mt) {
    if (mt.pbFormat) { CoTaskMemFree(mt.pbFormat); mt.pbFormat = NULL; }
    if (mt.pUnk) { mt.pUnk->Release(); mt.pUnk = NULL; }
    mt.cbFormat = 0;
}

COutputPin::COutputPin(CFilter* pFilter)
    : m_cRef(1), m_pFilter(pFilter), m_pConnectedPin(NULL), m_pMemInputPin(NULL),
      m_pAllocator(NULL), m_hSharedMem(NULL), m_pSharedFrame(NULL),
      m_bFlushing(FALSE), m_bShutdown(FALSE), m_hThread(NULL), m_dwThreadId(0) {
    ZeroMemory(&m_mt, sizeof(m_mt));
}

COutputPin::~COutputPin() {
    Shutdown();
}

STDMETHODIMP COutputPin::QueryInterface(REFIID riid, void** ppv) {
    if (riid == IID_IUnknown || riid == IID_IPin) { *ppv = (IPin*)this; AddRef(); return S_OK; }
    if (riid == IID_IMemOutputPin_Manual) { *ppv = (IMemOutputPin*)this; AddRef(); return S_OK; }
    if (riid == IID_IQualityControl) { *ppv = (IQualityControl*)this; AddRef(); return S_OK; }
    if (riid == IID_IAMStreamConfig) { *ppv = (IAMStreamConfig*)this; AddRef(); return S_OK; }
    if (riid == IID_IKsPropertySet) { *ppv = (IKsPropertySet*)this; AddRef(); return S_OK; }
    *ppv = NULL;
    return E_NOINTERFACE;
}

STDMETHODIMP_(ULONG) COutputPin::AddRef() { return InterlockedIncrement(&m_cRef); }
STDMETHODIMP_(ULONG) COutputPin::Release() {
    LONG c = InterlockedDecrement(&m_cRef);
    if (c == 0) delete this;
    return c;
}

HRESULT COutputPin::CreateInstance(CFilter* pFilter, COutputPin** ppPin) {
    COutputPin* p = new COutputPin(pFilter);
    if (!p) return E_OUTOFMEMORY;
    HRESULT hr = p->Initialize();
    if (FAILED(hr)) { delete p; return hr; }
    *ppPin = p;
    return S_OK;
}

HRESULT COutputPin::Initialize() {
    m_pSharedFrame = SharedMemory_Open();
    if (!m_pSharedFrame) return E_FAIL;
    BuildMediaType(&m_mt);
    return S_OK;
}

HRESULT COutputPin::Shutdown() {
    if (m_bShutdown) return S_OK;
    m_bShutdown = TRUE;
    if (m_hThread) {
        WaitForSingleObject(m_hThread, 2000);
        CloseHandle(m_hThread);
        m_hThread = NULL;
    }
    if (m_pAllocator) { m_pAllocator->Release(); m_pAllocator = NULL; }
    if (m_pMemInputPin) { m_pMemInputPin->Release(); m_pMemInputPin = NULL; }
    if (m_pConnectedPin) { m_pConnectedPin->Release(); m_pConnectedPin = NULL; }
    FreeMediaType(m_mt);
    SharedMemory_Close(m_pSharedFrame);
    m_pSharedFrame = NULL;
    return S_OK;
}

void COutputPin::BuildMediaType(AM_MEDIA_TYPE* pmt) {
    VIDEOINFOHEADER* vih = (VIDEOINFOHEADER*)CoTaskMemAlloc(sizeof(VIDEOINFOHEADER));
    ZeroMemory(vih, sizeof(VIDEOINFOHEADER));
    vih->bmiHeader.biSize = sizeof(BITMAPINFOHEADER);
    vih->bmiHeader.biWidth = SHARED_FRAME_WIDTH;
    vih->bmiHeader.biHeight = SHARED_FRAME_HEIGHT;
    vih->bmiHeader.biPlanes = 1;
    vih->bmiHeader.biBitCount = 24;
    vih->bmiHeader.biCompression = BI_RGB;
    vih->bmiHeader.biSizeImage = SHARED_FRAME_WIDTH * SHARED_FRAME_HEIGHT * 3;
    vih->AvgTimePerFrame = 333333;

    pmt->majortype = MEDIATYPE_Video;
    pmt->subtype = MEDIASUBTYPE_RGB24;
    pmt->formattype = FORMAT_VideoInfo;
    pmt->bFixedSizeSamples = TRUE;
    pmt->bTemporalCompression = FALSE;
    pmt->lSampleSize = SHARED_FRAME_WIDTH * SHARED_FRAME_HEIGHT * 3;
    pmt->cbFormat = sizeof(VIDEOINFOHEADER);
    pmt->pbFormat = (BYTE*)vih;
}

STDMETHODIMP COutputPin::Connect(IPin* pReceivePin, const AM_MEDIA_TYPE* pmt) {
    if (!pReceivePin) return E_POINTER;
    if (m_pConnectedPin) return VFW_E_ALREADY_CONNECTED;
    const AM_MEDIA_TYPE* selected = pmt ? pmt : &m_mt;
    const LONG sampleSize = (LONG)selected->lSampleSize;
    ALLOCATOR_PROPERTIES props = { 0, 0, 1, 0 };
    ALLOCATOR_PROPERTIES requirements = { 0, 0, 1, 0 };
    ALLOCATOR_PROPERTIES actual = { 0, 0, 0, 0 };
    HRESULT hr = QueryAccept(selected);
    if (FAILED(hr)) return hr;

    hr = pReceivePin->ReceiveConnection(this, selected);
    if (FAILED(hr)) return hr;

    pReceivePin->AddRef();
    m_pConnectedPin = pReceivePin;
    hr = pReceivePin->QueryInterface(IID_IMemInputPin_Manual, (void**)&m_pMemInputPin);
    if (FAILED(hr)) goto fail;

    hr = m_pMemInputPin->GetAllocator(&m_pAllocator);
    if (FAILED(hr) || !m_pAllocator) {
        m_pAllocator = NULL;
        hr = CoCreateInstance(CLSID_MemoryAllocator, NULL, CLSCTX_INPROC_SERVER,
                              IID_PPV_ARGS(&m_pAllocator));
        if (FAILED(hr)) goto fail;
    }

    if (SUCCEEDED(m_pMemInputPin->GetAllocatorRequirements(&requirements))) {
        props = requirements;
    }
    if (props.cBuffers < 2) props.cBuffers = 2;
    if (props.cbBuffer < sampleSize) props.cbBuffer = sampleSize;
    if (props.cbAlign < 1) props.cbAlign = 1;
    hr = m_pAllocator->SetProperties(&props, &actual);
    if (FAILED(hr) || actual.cbBuffer < sampleSize) { if (SUCCEEDED(hr)) hr = E_FAIL; goto fail; }
    hr = m_pMemInputPin->NotifyAllocator(m_pAllocator, FALSE);
    if (FAILED(hr)) goto fail;
    hr = m_pAllocator->Commit();
    if (FAILED(hr)) goto fail;

    if (selected != &m_mt) {
        AM_MEDIA_TYPE connectedType = {};
        hr = CopyMediaType(&connectedType, selected);
        if (FAILED(hr)) goto fail;
        FreeMediaType(m_mt);
        m_mt = connectedType;
    }

    m_bShutdown = FALSE;
    m_hThread = CreateThread(NULL, 0, ThreadProc, this, 0, &m_dwThreadId);
    if (!m_hThread) { hr = HRESULT_FROM_WIN32(GetLastError()); goto fail; }
    return S_OK;

fail:
    if (m_pAllocator) { m_pAllocator->Decommit(); m_pAllocator->Release(); m_pAllocator = NULL; }
    if (m_pMemInputPin) { m_pMemInputPin->Release(); m_pMemInputPin = NULL; }
    if (m_pConnectedPin) { m_pConnectedPin->Release(); m_pConnectedPin = NULL; }
    pReceivePin->Disconnect();
    return hr;
}

STDMETHODIMP COutputPin::ReceiveConnection(IPin*, const AM_MEDIA_TYPE*) {
    return E_UNEXPECTED;
}

STDMETHODIMP COutputPin::Disconnect() {
    if (!m_pConnectedPin) return S_FALSE;
    m_bShutdown = TRUE;
    if (m_hThread) {
        WaitForSingleObject(m_hThread, 2000);
        CloseHandle(m_hThread);
        m_hThread = NULL;
    }
    if (m_pAllocator) { m_pAllocator->Decommit(); m_pAllocator->Release(); m_pAllocator = NULL; }
    if (m_pMemInputPin) { m_pMemInputPin->Release(); m_pMemInputPin = NULL; }
    IPin* connected = m_pConnectedPin;
    m_pConnectedPin = NULL;
    m_bShutdown = FALSE;
    connected->Release();
    return S_OK;
}

STDMETHODIMP COutputPin::ConnectedTo(IPin** pPin) {
    if (!pPin) return E_POINTER;
    *pPin = m_pConnectedPin;
    if (m_pConnectedPin) m_pConnectedPin->AddRef();
    return m_pConnectedPin ? S_OK : VFW_E_NOT_CONNECTED;
}

STDMETHODIMP COutputPin::ConnectionMediaType(AM_MEDIA_TYPE* pmt) {
    if (!IsConnected()) return VFW_E_NOT_CONNECTED;
    return CopyMediaType(pmt, &m_mt);
}

STDMETHODIMP COutputPin::QueryPinInfo(PIN_INFO* pInfo) {
    if (!pInfo) return E_POINTER;
    pInfo->pFilter = m_pFilter;
    pInfo->dir = PINDIR_OUTPUT;
    wcscpy_s(pInfo->achName, _countof(pInfo->achName), FILTER_PIN_NAME);
    m_pFilter->AddRef();
    return S_OK;
}

STDMETHODIMP COutputPin::QueryDirection(PIN_DIRECTION* pPinDir) {
    if (!pPinDir) return E_POINTER;
    *pPinDir = PINDIR_OUTPUT;
    return S_OK;
}

STDMETHODIMP COutputPin::QueryId(LPWSTR* Id) {
    if (!Id) return E_POINTER;
    *Id = (LPWSTR)CoTaskMemAlloc(64);
    if (!*Id) return E_OUTOFMEMORY;
    wcscpy_s(*Id, 64, FILTER_PIN_NAME);
    return S_OK;
}

STDMETHODIMP COutputPin::QueryAccept(const AM_MEDIA_TYPE* pmt) {
    if (!pmt) return E_POINTER;
    if (pmt->majortype != MEDIATYPE_Video) return VFW_E_TYPE_NOT_ACCEPTED;
    if (pmt->subtype != MEDIASUBTYPE_RGB24) return VFW_E_TYPE_NOT_ACCEPTED;
    return S_OK;
}

STDMETHODIMP COutputPin::EnumMediaTypes(IEnumMediaTypes** ppEnum) {
    if (!ppEnum) return E_POINTER;
    *ppEnum = new CEnumMediaTypes(m_mt);
    return *ppEnum ? S_OK : E_OUTOFMEMORY;
}

STDMETHODIMP COutputPin::QueryInternalConnections(IPin**, ULONG* nPin) {
    if (nPin) *nPin = 0;
    return S_OK;
}

STDMETHODIMP COutputPin::EndOfStream() { return S_OK; }
STDMETHODIMP COutputPin::NewSegment(REFERENCE_TIME, REFERENCE_TIME, double) { return S_OK; }
STDMETHODIMP COutputPin::BeginFlush() { m_bFlushing = TRUE; return S_OK; }
STDMETHODIMP COutputPin::EndFlush() { m_bFlushing = FALSE; return S_OK; }

STDMETHODIMP COutputPin::GetNumberOfBuffers(uint32_t* pNumBuffers) {
    if (!pNumBuffers) return E_POINTER;
    *pNumBuffers = 3;
    return S_OK;
}

STDMETHODIMP COutputPin::GetBuffer(IMediaSample** ppSample, REFERENCE_TIME* pStartTime, REFERENCE_TIME* pEndTime, DWORD dwFlags) {
    if (!ppSample) return E_POINTER;
    if (!m_pAllocator) return E_FAIL;
    return m_pAllocator->GetBuffer(ppSample, pStartTime, pEndTime, dwFlags);
}

STDMETHODIMP COutputPin::ReleaseBuffer(IMediaSample*) { return S_OK; }

STDMETHODIMP COutputPin::Notify(IBaseFilter*, Quality) { return S_OK; }
STDMETHODIMP COutputPin::SetSink(IQualityControl*) { return S_OK; }

STDMETHODIMP COutputPin::GetFormat(AM_MEDIA_TYPE** ppmt) {
    if (!ppmt) return E_POINTER;
    *ppmt = (AM_MEDIA_TYPE*)CoTaskMemAlloc(sizeof(AM_MEDIA_TYPE));
    if (!*ppmt) return E_OUTOFMEMORY;
    return CopyMediaType(*ppmt, &m_mt);
}

STDMETHODIMP COutputPin::GetNumberOfCapabilities(int* piCount, int* piSize) {
    if (!piCount || !piSize) return E_POINTER;
    *piCount = 1;
    *piSize = sizeof(VIDEO_STREAM_CONFIG_CAPS);
    return S_OK;
}

STDMETHODIMP COutputPin::GetStreamCaps(int iIndex, AM_MEDIA_TYPE** ppmt, BYTE* pSCC) {
    if (iIndex != 0 || !ppmt) return E_INVALIDARG;
    *ppmt = (AM_MEDIA_TYPE*)CoTaskMemAlloc(sizeof(AM_MEDIA_TYPE));
    if (!*ppmt) return E_OUTOFMEMORY;
    HRESULT hr = CopyMediaType(*ppmt, &m_mt);
    if (FAILED(hr)) { CoTaskMemFree(*ppmt); *ppmt = NULL; return hr; }
    if (pSCC) {
        VIDEO_STREAM_CONFIG_CAPS* caps = (VIDEO_STREAM_CONFIG_CAPS*)pSCC;
        ZeroMemory(caps, sizeof(*caps));
        caps->guid = FORMAT_VideoInfo;
        caps->VideoStandard = AnalogVideo_None;
        caps->InputSize.cx = SHARED_FRAME_WIDTH;
        caps->InputSize.cy = SHARED_FRAME_HEIGHT;
        caps->MinOutputSize.cx = SHARED_FRAME_WIDTH;
        caps->MinOutputSize.cy = SHARED_FRAME_HEIGHT;
        caps->MaxOutputSize.cx = SHARED_FRAME_WIDTH;
        caps->MaxOutputSize.cy = SHARED_FRAME_HEIGHT;
        caps->MinFrameInterval = 333333;
        caps->MaxFrameInterval = 333333;
        caps->MinBitsPerSecond = 1000000;
        caps->MaxBitsPerSecond = 10000000;
    }
    return S_OK;
}

STDMETHODIMP COutputPin::SetFormat(AM_MEDIA_TYPE* pmt) {
    return QueryAccept(pmt);
}

STDMETHODIMP COutputPin::Get(REFGUID propSet, DWORD id, LPVOID, DWORD,
                             LPVOID propertyData, DWORD dataLength, DWORD* bytesReturned) {
    if (propSet != AMPROPSETID_Pin || id != AMPROPERTY_PIN_CATEGORY) return E_PROP_ID_UNSUPPORTED;
    if (!propertyData || dataLength < sizeof(GUID)) return E_INVALIDARG;
    *(GUID*)propertyData = PIN_CATEGORY_CAPTURE;
    if (bytesReturned) *bytesReturned = sizeof(GUID);
    return S_OK;
}

STDMETHODIMP COutputPin::Set(REFGUID, DWORD, LPVOID, DWORD, LPVOID, DWORD) {
    return E_NOTIMPL;
}

STDMETHODIMP COutputPin::QuerySupported(REFGUID propSet, DWORD id, DWORD* typeSupport) {
    if (!typeSupport) return E_POINTER;
    *typeSupport = 0;
    if (propSet != AMPROPSETID_Pin || id != AMPROPERTY_PIN_CATEGORY) return E_PROP_ID_UNSUPPORTED;
    *typeSupport = KSPROPERTY_SUPPORT_GET;
    return S_OK;
}

DWORD WINAPI COutputPin::ThreadProc(LPVOID pParam) {
    COutputPin* p = (COutputPin*)pParam;
    p->DeliveryLoop();
    return 0;
}

void COutputPin::DeliveryLoop() {
    while (!m_bShutdown) {
        if (m_bFlushing || !m_pMemInputPin || !m_pAllocator) {
            Sleep(10);
            continue;
        }

        IMediaSample* pSample = NULL;
        HRESULT hr = m_pAllocator->GetBuffer(&pSample, NULL, NULL, 0);
        if (FAILED(hr) || !pSample) {
            Sleep(10);
            continue;
        }

        BYTE* pData = NULL;
        hr = pSample->GetPointer(&pData);
        if (FAILED(hr) || !pData) {
            pSample->Release();
            continue;
        }

        DWORD w, h;
        DWORD64 ts;
        if (SharedMemory_ReadFrame(m_pSharedFrame, pData, m_mt.lSampleSize, &w, &h, &ts)) {
            REFERENCE_TIME start = m_rtNextSample;
            REFERENCE_TIME end = start + 333333;
            m_rtNextSample = end;
            pSample->SetTime(&start, &end);
            pSample->SetSyncPoint(TRUE);
            pSample->SetDiscontinuity(FALSE);
            pSample->SetActualDataLength(m_mt.lSampleSize);
            m_pMemInputPin->Receive(pSample);
            Sleep(33);
        } else {
            Sleep(10);
        }
        pSample->Release();
    }
}

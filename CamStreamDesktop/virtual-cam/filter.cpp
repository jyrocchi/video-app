#include "filter.h"
#include "output_pin.h"
#include "virtual-cam.h"

CFilter::CFilter() : m_cRef(1), m_State(State_Stopped), m_pClock(NULL),
                     m_pGraph(NULL), m_pOutputPin(NULL), m_pName(NULL) {
}

CFilter::~CFilter() {
    if (m_pOutputPin) m_pOutputPin->Release();
    if (m_pClock) m_pClock->Release();
    if (m_pName) CoTaskMemFree(m_pName);
}

STDMETHODIMP CFilter::QueryInterface(REFIID riid, void** ppv) {
    if (riid == IID_IUnknown || riid == IID_IPersist || riid == IID_IBaseFilter) {
        *ppv = (IBaseFilter*)this;
        AddRef();
        return S_OK;
    }
    if (riid == IID_IAMovieSetup) {
        *ppv = (IAMovieSetup*)this;
        AddRef();
        return S_OK;
    }
    return *ppv = NULL, E_NOINTERFACE;
}

STDMETHODIMP_(ULONG) CFilter::AddRef() { return InterlockedIncrement(&m_cRef); }
STDMETHODIMP_(ULONG) CFilter::Release() {
    LONG c = InterlockedDecrement(&m_cRef);
    if (c == 0) delete this;
    return c;
}

STDMETHODIMP CFilter::GetClassID(CLSID* pClassID) {
    if (!pClassID) return E_POINTER;
    *pClassID = CLSID_CamStreamVirtualCam;
    return S_OK;
}

STDMETHODIMP CFilter::Stop() { m_State = State_Stopped; return S_OK; }
STDMETHODIMP CFilter::Pause() { m_State = State_Paused; return S_OK; }
STDMETHODIMP CFilter::Run(REFERENCE_TIME) { m_State = State_Running; return S_OK; }
STDMETHODIMP CFilter::GetState(DWORD, FILTER_STATE* State) {
    if (!State) return E_POINTER;
    *State = m_State;
    return S_OK;
}
STDMETHODIMP CFilter::SetSyncSource(IReferenceClock* pClock) {
    if (m_pClock) m_pClock->Release();
    m_pClock = pClock;
    if (m_pClock) m_pClock->AddRef();
    return S_OK;
}
STDMETHODIMP CFilter::GetSyncSource(IReferenceClock** pClock) {
    if (!pClock) return E_POINTER;
    *pClock = m_pClock;
    if (m_pClock) m_pClock->AddRef();
    return S_OK;
}

class CEnumPins : public IEnumPins {
public:
    CEnumPins(COutputPin* pPin) : m_pPin(pPin), m_cRef(1), m_index(0) { if (m_pPin) m_pPin->AddRef(); }
    ~CEnumPins() { if (m_pPin) m_pPin->Release(); }

    STDMETHODIMP QueryInterface(REFIID riid, void** ppv) override {
        if (riid == IID_IUnknown || riid == IID_IEnumPins) {
            *ppv = (IEnumPins*)this;
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
    STDMETHODIMP Next(ULONG cPins, IPin** ppPins, ULONG* pcFetched) override {
        ULONG fetched = 0;
        if (m_index == 0 && cPins > 0) {
            *ppPins = m_pPin;
            m_pPin->AddRef();
            fetched = 1;
            m_index++;
        }
        if (pcFetched) *pcFetched = fetched;
        return fetched == cPins ? S_OK : S_FALSE;
    }
    STDMETHODIMP Skip(ULONG cPins) override {
        m_index += cPins;
        return m_index <= 1 ? S_OK : S_FALSE;
    }
    STDMETHODIMP Reset() override { m_index = 0; return S_OK; }
    STDMETHODIMP Clone(IEnumPins** ppEnum) override {
        *ppEnum = new CEnumPins(m_pPin);
        return S_OK;
    }
private:
    COutputPin* m_pPin;
    LONG m_cRef;
    ULONG m_index;
};

STDMETHODIMP CFilter::EnumPins(IEnumPins** ppEnum) {
    if (!ppEnum) return E_POINTER;
    *ppEnum = new CEnumPins(m_pOutputPin);
    return S_OK;
}

STDMETHODIMP CFilter::FindPin(LPCWSTR id, IPin** ppPin) {
    if (!ppPin) return E_POINTER;
    *ppPin = NULL;
    if (!id || wcscmp(id, FILTER_PIN_NAME) != 0) return VFW_E_NOT_FOUND;
    if (!m_pOutputPin) return VFW_E_NOT_FOUND;
    m_pOutputPin->AddRef();
    *ppPin = (IPin*)m_pOutputPin;
    return S_OK;
}

STDMETHODIMP CFilter::QueryFilterInfo(FILTER_INFO* pInfo) {
    if (!pInfo) return E_POINTER;
    wcscpy_s(pInfo->achName, _countof(pInfo->achName), FILTER_FRIENDLY_NAME);
    pInfo->pGraph = m_pGraph;
    if (m_pGraph) m_pGraph->AddRef();
    return S_OK;
}

STDMETHODIMP CFilter::JoinFilterGraph(IFilterGraph* pGraph, LPCWSTR pName) {
    m_pGraph = pGraph;
    if (pName && !m_pName) {
        size_t len = wcslen(pName) + 1;
        m_pName = (LPWSTR)CoTaskMemAlloc(len * sizeof(WCHAR));
        wcscpy(m_pName, pName);
    }
    return S_OK;
}

STDMETHODIMP CFilter::QueryVendorInfo(LPWSTR* pVendorInfo) {
    if (!pVendorInfo) return E_POINTER;
    *pVendorInfo = (LPWSTR)CoTaskMemAlloc(32 * sizeof(WCHAR));
    wcscpy(*pVendorInfo, L"JyroCam");
    return S_OK;
}

HRESULT CFilter::CreateInstance(CFilter** ppFilter) {
    CFilter* p = new CFilter();
    if (!p) return E_OUTOFMEMORY;
    HRESULT hr = COutputPin::CreateInstance(p, &p->m_pOutputPin);
    if (FAILED(hr)) { delete p; return hr; }
    *ppFilter = p;
    return S_OK;
}

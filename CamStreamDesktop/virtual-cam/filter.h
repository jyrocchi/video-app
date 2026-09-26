#pragma once
#include <windows.h>
#include <objbase.h>
#include <strmif.h>

class COutputPin;

class CFilter : public IBaseFilter, public IAMovieSetup {
public:
    CFilter();
    ~CFilter();

    STDMETHODIMP QueryInterface(REFIID riid, void** ppv) override;
    STDMETHODIMP_(ULONG) AddRef() override;
    STDMETHODIMP_(ULONG) Release() override;

    // IBaseFilter
    STDMETHODIMP GetClassID(CLSID* pClassID) override;
    STDMETHODIMP Stop() override;
    STDMETHODIMP Pause() override;
    STDMETHODIMP Run(REFERENCE_TIME tStart) override;
    STDMETHODIMP GetState(DWORD dwMilliSecsTimeout, FILTER_STATE* State) override;
    STDMETHODIMP SetSyncSource(IReferenceClock* pClock) override;
    STDMETHODIMP GetSyncSource(IReferenceClock** pClock) override;
    STDMETHODIMP EnumPins(IEnumPins** ppEnum) override;
    STDMETHODIMP FindPin(LPCWSTR Id, IPin** ppPin) override;
    STDMETHODIMP QueryFilterInfo(FILTER_INFO* pInfo) override;
    STDMETHODIMP JoinFilterGraph(IFilterGraph* pGraph, LPCWSTR pName) override;
    STDMETHODIMP QueryVendorInfo(LPWSTR* pVendorInfo) override;

    // IAMovieSetup
    STDMETHODIMP Register() override { return S_OK; }
    STDMETHODIMP Unregister() override { return S_OK; }

    static HRESULT CreateInstance(CFilter** ppFilter);

private:
    LONG m_cRef;
    FILTER_STATE m_State;
    IReferenceClock* m_pClock;
    IFilterGraph* m_pGraph;
    COutputPin* m_pOutputPin;
    LPWSTR m_pName;
};
#pragma once
#define INITGUID
#include <windows.h>
#include <objbase.h>
#include <combaseapi.h>
#include <dshow.h>
#include <strmif.h>
#include <amvideo.h>
#include <dvdmedia.h>
#include <ks.h>
#include <ksproxy.h>
#include <cstdint>

// IMemOutputPin declaration - not in current SDK headers, declare manually
#ifndef __IMemOutputPin_INTERFACE_DEFINED__
#define __IMemOutputPin_INTERFACE_DEFINED__
MIDL_INTERFACE("6C711001-5731-11D0-8384-00A0C911B849")
IMemOutputPin : public IUnknown
{
public:
    virtual HRESULT STDMETHODCALLTYPE GetNumberOfBuffers(uint32_t* pNumBuffers) = 0;
    virtual HRESULT STDMETHODCALLTYPE GetBuffer(IMediaSample** ppSample, REFERENCE_TIME* pStartTime, REFERENCE_TIME* pEndTime, DWORD dwFlags) = 0;
    virtual HRESULT STDMETHODCALLTYPE ReleaseBuffer(IMediaSample* pSample) = 0;
};
EXTERN_C const GUID IID_IMemOutputPin_Manual;
#endif

// IMemInputPin declaration
#ifndef __IMemInputPin_INTERFACE_DEFINED__
#define __IMemInputPin_INTERFACE_DEFINED__
MIDL_INTERFACE("56A8689D-0AD4-11CE-B03A-0020AF0BA770")
IMemInputPin : public IUnknown
{
public:
    virtual HRESULT STDMETHODCALLTYPE GetAllocator(IMemAllocator** ppAllocator) = 0;
    virtual HRESULT STDMETHODCALLTYPE NotifyAllocator(IMemAllocator* pAllocator, BOOL bReadOnly) = 0;
    virtual HRESULT STDMETHODCALLTYPE GetAllocatorRequirements(ALLOCATOR_PROPERTIES* pProps) = 0;
    virtual HRESULT STDMETHODCALLTYPE Receive(IMediaSample* pSample) = 0;
    virtual HRESULT STDMETHODCALLTYPE ReceiveMultiple(IMediaSample** pSamples, long nSamples, long* nSamplesProcessed) = 0;
    virtual HRESULT STDMETHODCALLTYPE ReceiveCanBlock() = 0;
};
#endif
extern "C" const GUID IID_IMemInputPin_Manual;

// Forward declaration of shared memory functions
struct SharedFrame;
extern "C" SharedFrame* SharedMemory_Open();
extern "C" void SharedMemory_Close(SharedFrame*);
extern "C" BOOL SharedMemory_ReadFrame(SharedFrame*, BYTE*, DWORD, DWORD*, DWORD*, DWORD64*);

extern "C" HRESULT CopyMediaType(AM_MEDIA_TYPE* pDest, const AM_MEDIA_TYPE* pSrc);
extern "C" void FreeMediaType(AM_MEDIA_TYPE& mt);

class CFilter;

class COutputPin : public IPin, public IMemOutputPin, public IQualityControl,
                   public IAMStreamConfig, public IKsPropertySet {
public:
    COutputPin(CFilter* pFilter);
    ~COutputPin();

    STDMETHODIMP QueryInterface(REFIID riid, void** ppv) override;
    STDMETHODIMP_(ULONG) AddRef() override;
    STDMETHODIMP_(ULONG) Release() override;

    STDMETHODIMP Connect(IPin* pReceivePin, const AM_MEDIA_TYPE* pmt) override;
    STDMETHODIMP ReceiveConnection(IPin* pConnector, const AM_MEDIA_TYPE* pmt) override;
    STDMETHODIMP Disconnect() override;
    STDMETHODIMP ConnectedTo(IPin** pPin) override;
    STDMETHODIMP ConnectionMediaType(AM_MEDIA_TYPE* pmt) override;
    STDMETHODIMP QueryPinInfo(PIN_INFO* pInfo) override;
    STDMETHODIMP QueryDirection(PIN_DIRECTION* pPinDir) override;
    STDMETHODIMP QueryId(LPWSTR* Id) override;
    STDMETHODIMP QueryAccept(const AM_MEDIA_TYPE* pmt) override;
    STDMETHODIMP EnumMediaTypes(IEnumMediaTypes** ppEnum) override;
    STDMETHODIMP QueryInternalConnections(IPin** apPin, ULONG* nPin) override;
    STDMETHODIMP EndOfStream() override;
    STDMETHODIMP NewSegment(REFERENCE_TIME tStart, REFERENCE_TIME tStop, double dRate) override;
    STDMETHODIMP BeginFlush() override;
    STDMETHODIMP EndFlush() override;

    STDMETHODIMP GetNumberOfBuffers(uint32_t* pNumBuffers) override;
    STDMETHODIMP GetBuffer(IMediaSample** ppSample, REFERENCE_TIME* pStartTime, REFERENCE_TIME* pEndTime, DWORD dwFlags) override;
    STDMETHODIMP ReleaseBuffer(IMediaSample* pSample) override;

    STDMETHODIMP Notify(IBaseFilter* pSelf, Quality q) override;
    STDMETHODIMP SetSink(IQualityControl* piqc) override;

    STDMETHODIMP GetFormat(AM_MEDIA_TYPE** ppmt) override;
    STDMETHODIMP GetNumberOfCapabilities(int* piCount, int* piSize) override;
    STDMETHODIMP GetStreamCaps(int iIndex, AM_MEDIA_TYPE** ppmt, BYTE* pSCC) override;
    STDMETHODIMP SetFormat(AM_MEDIA_TYPE* pmt) override;

    STDMETHODIMP Get(REFGUID PropSet, DWORD Id, LPVOID InstanceData, DWORD InstanceLength,
                     LPVOID PropertyData, DWORD DataLength, DWORD* BytesReturned) override;
    STDMETHODIMP Set(REFGUID PropSet, DWORD Id, LPVOID InstanceData, DWORD InstanceLength,
                     LPVOID PropertyData, DWORD DataLength) override;
    STDMETHODIMP QuerySupported(REFGUID PropSet, DWORD Id, DWORD* TypeSupport) override;

    BOOL IsConnected() { return m_pConnectedPin != NULL; }

    HRESULT Initialize();
    HRESULT Shutdown();

    static HRESULT CreateInstance(CFilter* pFilter, COutputPin** ppPin);

private:
    LONG m_cRef;
    CFilter* m_pFilter;
    IPin* m_pConnectedPin;
    IMemInputPin* m_pMemInputPin;
    AM_MEDIA_TYPE m_mt;
    IMemAllocator* m_pAllocator;
    HANDLE m_hSharedMem;
    SharedFrame* m_pSharedFrame;
    volatile BOOL m_bFlushing;
    volatile BOOL m_bShutdown;
    HANDLE m_hThread;
    DWORD m_dwThreadId;
    REFERENCE_TIME m_rtNextSample = 0;

    static DWORD WINAPI ThreadProc(LPVOID pParam);
    void DeliveryLoop();
    void BuildMediaType(AM_MEDIA_TYPE* pmt);
};

package com.pagatodo.sunmi.poslib

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import com.pagatodo.sunmi.poslib.PosLib.Companion.TAG
import com.pagatodo.sunmi.poslib.config.PinPadConfigV3
import com.pagatodo.sunmi.poslib.interfaces.SunmiTrxListener
import com.pagatodo.sunmi.poslib.model.DataCard
import com.pagatodo.sunmi.poslib.model.Results
import com.pagatodo.sunmi.poslib.util.*
import com.sunmi.pay.hardware.aidl.AidlConstants
import com.sunmi.pay.hardware.aidlv2.pinpad.PinPadListenerV2
import net.fullcarga.android.api.data.respuesta.OperacionSiguiente
import net.fullcarga.android.api.data.respuesta.RespuestaTrxCierreTurno
import net.fullcarga.android.api.exc.SincronizacionRequeridaException
import java.nio.charset.Charset

class SunmiTrxWrapper(owner: LifecycleOwner, val test: Boolean = false) :
    SunmiTransaction() {

    private val mTransactionData by lazy { sunmiListener.createTransactionData() }
    private lateinit var dataCard: DataCard
    private val sunmiListener: SunmiTrxListener<*>
    private var requestTransaction: RespuestaTrxCierreTurno? = null
    private var forceCheckCard: Int = -1
    private var nextOperation: OperacionSiguiente? = null
    private var listenerPinOk: SunmiTrxListener<*>? = null

    init {
        if (owner is SunmiTrxListener<*>)
            sunmiListener = owner
        else
            throw InstantiationException("Owner must be instance of SunmiTrxListener.")
        sunmiListener.getVmodelPCI().pciViewModel.observe(owner, pciObserver)
        sunmiListener.getVmodelPCI().syncViewModel.observe(owner, syncObserver)
    }

    fun initTransaction() {
        setTerminalParams()
        forceCheckCard = -1
        sunmiListener.onDialogRequestCard(cardTypes = getCheckCardType())
        super.clearVars()
        super.startEmvProcess()
    }

    private fun resendTransaction(title: String? = null) {
        sunmiListener.onDialogRequestCard(title, getCheckCardType())
        super.startEmvProcess()
    }

    fun getPin() {
        getPin(dataCard)
    }

    override fun goOnlineProcess(dataCard: DataCard) {
        if(isRequestPin && mPinType == 1){
            listenerPinOk = sunmiListener//to avoid show dialog process on error oline
            sunmiListener.onFailureEmv(PosResult.InfoPinOk){ listenerPinOk?.onDialogProcessOnline(dataCard = dataCard) }
        } else
            sunmiListener.onDialogProcessOnline(dataCard = dataCard)
        this.dataCard = dataCard.apply { PosLogger.d(TAG, this.toString()) }
        sunmiListener.onDismissRequestCard()
        sunmiListener.onPurchase(dataCard)
    }

    override fun onFailure(result: PosResult) {
        PosLogger.d(TAG, "onFailure: $result")
        sunmiListener.onDismissRequestCard()
        sunmiListener.onDismissRequestOnline()
        BuzzerUtil.doBeep(result)
        when (result) {
            PosResult.DoSyncOperation, PosResult.ErrorCheckPresentCard -> {
                if(this::dataCard.isInitialized){
                    sunmiListener.onDialogProcessOnline(result.message, dataCard)
                    sunmiListener.onSync(dataCard)
                } else sunmiListener.onFailureEmv(PosResult.ErrorCheckCard){}
            }
            PosResult.ReplaceCard, PosResult.SeePhone, PosResult.CardNoSupported,
            PosResult.CardDenial, PosResult.NfcTerminated,  PosResult.TransTerminate,
            PosResult.FinalSelectApp, PosResult.DataCardWithError, PosResult.NoCommonAppNfc -> {
                sunmiListener.onFailureEmv(result) { message -> resendTransaction(message) }
            }
            PosResult.NextOperation -> {
                nextOperation?.apply {
                    sunmiListener.onFailureOnline(result) { message -> resendTransaction(message) }
                }?: run { sunmiListener.onFailureOnline(result){} }
                nextOperation = null
            }
            PosResult.FallBack, PosResult.FallBackCommonApp -> {
                if(sunmiListener.isPossibleFallback()) {
                    allowFallback = true
                    forceCheckCard = mcrOnlyCheckCard
                    sunmiListener.onFailureEmv(result) { message -> resendTransaction(message) }
                } else  sunmiListener.onFailureEmv(PosResult.ErrorCheckCard){ checkAndRemoveCard() }
            }
            PosResult.OtherInterface -> {
                forceCheckCard = rfOffCheckCard
                sunmiListener.onFailureEmv(result) { message -> resendTransaction(message) }
            }
            PosResult.ErrorRepeatCall -> {
                sunmiListener.onDialogRequestCard(cardTypes = getCheckCardType())
            }
            PosResult.OnlineError -> {
                onFailureOnline(result)
            }
            else -> sunmiListener.onFailureEmv(result){ checkAndRemoveCard() }
        }
    }

    private fun onFailureOnline(result: PosResult){
        listenerPinOk = null
        sunmiListener.onFailureOnline(result) {
            checkAndRemoveCard()
        }
    }

    override fun onSuccessOnline() {
        sunmiListener.onDismissRequestOnline()
        sunmiListener.onSuccessOnline {
            checkAndRemoveCard {
                if (isRequestSignature || PciUtils.emvRequestSignature(dataCard.tlvData) || sunmiListener.requireSignature(dataCard))
                    sunmiListener.onShowSingDialog { sign ->
                        sunmiListener.onShowTicketDialog(requestTransaction, dataCard, sign)
                    }
                else
                    sunmiListener.onShowTicketDialog(requestTransaction, dataCard)
            }
        }
    }

    override fun getCheckCardType(): Int {
        return if (forceCheckCard == -1)
            sunmiListener.checkCardTypes()
        else
            forceCheckCard
    }

    override fun pinMustBeForced() = sunmiListener.pinMustBeForced()

    override fun readingCard() = sunmiListener.showReading()

    override fun onShowPinPad(pinPadListener: PinPadListenerV2.Stub, pinPadConfig: PinPadConfigV3) {
        sunmiListener.onDismissRequestCard()
        sunmiListener.onShowPinPadDialog(pinPadListener, pinPadConfig)
    }

    override fun onSelectEmvApp(listEmvApps: List<String>, appSelect: (Int) -> Unit) {
        sunmiListener.onDismissRequestCard()
        sunmiListener.onShowSelectApp(listEmvApps, appSelect)
    }

    override fun getTransactionData() = mTransactionData

    override fun onRemoveCard() = sunmiListener.showRemoveCard(if(this::dataCard.isInitialized) dataCard else null)

    private fun doNxtOperation(response: RespuestaTrxCierreTurno){
        nextOperation = response.operacionSiguiente
        sunmiListener.doOperationNext(response.operacionSiguiente, response.campo60.first()) { message -> doNextOperation(message) }
    }

    private val pciObserver
        get() = Observer<Results<Any>> {
            when (it) {
                is Results.Success -> {
                    when {
                        it.data is RespuestaTrxCierreTurno -> {
                            requestTransaction = it.data
                            when {
                                hasNextOpr(it.data.operacionSiguiente) -> {
                                    doNxtOperation(it.data)
                                }
                                it.data.isCorrecta -> {
                                    val tags = String(it.data.campoTagsEmv, Charset.defaultCharset()).trim()
                                    finishOnlineProcessStatus(tlvString = tags, tlvResponse = Constants.TlvResponses.Approved)
                                }
                                else -> finishOnlineProcessStatus(tlvResponse = Constants.TlvResponses.Decline)
                            }
                        }
                        test -> finishOnlineProcessStatus(tlvResponse = Constants.TlvResponses.Approved)
                        else -> finishOnlineProcessStatus(tlvResponse = Constants.TlvResponses.Decline)
                    }
                }
                is Results.Failure -> {
                    val msgError = if (it.exception is SincronizacionRequeridaException) {
                        onFailure(PosResult.DoSyncOperation)
                        PosResult.DoSyncOperation.message
                    } else
                        it.exception.message
                    finishOnlineProcessStatus(tlvResponse = Constants.TlvResponses.Decline, message = msgError)
                }
            }
        }

    private fun hasNextOpr(nxtOpr: OperacionSiguiente?): Boolean {
        return nxtOpr?.let { it.procodIdNext > 0 } ?: false
    }

    fun cancelProcess() {
        cancelProcessEmv()
    }

    fun cancelProcessWithMessage() {
        cancelOperationWithMessage()
    }

    fun cancelProcessWithTvrError() {
        finishOnlineProcessStatus(tlvResponse = Constants.TlvResponses.Decline)
    }

    private val rfOffCheckCard: Int
        get() = AidlConstants.CardType.MAGNETIC.value or AidlConstants.CardType.IC.value

    private val mcrOnlyCheckCard: Int
        get() = AidlConstants.CardType.MAGNETIC.value

    private val syncObserver
        get() = Observer<Results<Any>> {
            when (it) {
                is Results.Success -> {
                    if (it.data is RespuestaTrxCierreTurno) {
                        PosLogger.d(TAG, "Success")
                        sunmiListener.onDismissRequestOnline()
                        onFailure(getPosResult(it.data.error, it.data.msjError))
                    } else onFailure(PosResult.SyncOperationSuccess)
                }
                is Results.Failure -> {
                    if(it.exception.message?.trim().equals("La operacion esta anulada"))
                        onFailure(PosResult.SyncOperationSuccess)
                    else
                        onFailure(PosResult.SyncOperationFailed)
                }
            }
        }
}
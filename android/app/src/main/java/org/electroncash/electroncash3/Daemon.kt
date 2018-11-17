package org.electroncash.electroncash3

import android.arch.lifecycle.MutableLiveData
import com.chaquo.python.Kwarg
import com.chaquo.python.PyException
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform


val py by lazy {
    Python.start(AndroidPlatform(app))
    Python.getInstance()
}
fun libMod(name: String) = py.getModule("electroncash.$name")!!
fun guiMod(name: String) = py.getModule("electroncash_gui.android.$name")!!

val libDaemon by lazy {
    val mod = guiMod("daemon")
    mod.callAttr("set_excepthook", mainHandler)
    mod
}

val WATCHDOG_INTERVAL = 1000L

lateinit var daemonModel: DaemonModel


fun initDaemon() {
    daemonModel = DaemonModel()
}


class DaemonModel {
    val commands = guiConsole.callAttr("AndroidCommands", app)!!
    val config = commands.get("config")!!
    val daemon = commands.get("daemon")!!
    val network = commands.get("network")!!
    val wallet: PyObject?
        get() = commands.get("wallet")

    lateinit var callback: Runnable
    lateinit var watchdog: Runnable

    val netStatus = MutableLiveData<NetworkStatus>()
    val walletName = MutableLiveData<String>()
    val walletBalance = MutableLiveData<Long>()
    val transactions = MutableLiveData<PyObject>()
    val addresses = MutableLiveData<PyObject>()

    init {
        initCallback()
        network.callAttr("register_callback", libDaemon.callAttr("make_callback", this),
                         guiConsole.get("CALLBACKS"))
        commands.callAttr("start")

        // This is still necessary even with the excepthook, in case a thread exits
        // non-exceptionally.
        watchdog = Runnable {
            for (thread in listOf(daemon, network)) {
                if (! thread.callAttr("is_alive").toJava(Boolean::class.java)) {
                    throw RuntimeException("$thread unexpectedly stopped")
                }
            }
            mainHandler.postDelayed(watchdog, WATCHDOG_INTERVAL)
        }
        watchdog.run()
    }

    fun initCallback() {
        callback = Runnable {
            if (network.callAttr("is_connected").toJava(Boolean::class.java)) {
                netStatus.value = NetworkStatus(
                    network.callAttr("get_local_height").toJava(Int::class.java),
                    network.callAttr("get_server_height").toJava(Int::class.java))
            } else {
                netStatus.value = null
            }

            val wallet = this.wallet
            if (wallet != null) {
                walletName.value = wallet.callAttr("basename").toString()
                if (wallet.callAttr("is_up_to_date").toJava(Boolean::class.java)) {
                    val balances = wallet.callAttr("get_balance")  // Returns (confirmed, unconfirmed, unmatured)
                    walletBalance.value = balances.callAttr("__getitem__", 0).toJava(Long::class.java)
                } else {
                    walletBalance.value = null
                }
                transactions.value = wallet.callAttr("export_history")
                addresses.value = guiAddresses.callAttr("get_addresses", wallet)
            } else {
                for (ld in listOf(walletName, walletBalance, transactions, addresses)) {
                    ld.value = null
                }
            }
        }
        onCallback("ui_create")  // Set initial LiveData values.
    }

    // This will sometimes be called on the main thread and sometimes on the network thread.
    fun onCallback(event: String) {
        if (event == "on_quotes") {
            fiatUpdate.postValue(Unit)
        } else {
            mainHandler.removeCallbacks(callback)  // Mitigate callback floods.
            mainHandler.post(callback)
        }
    }

    // TODO remove once Chaquopy provides better syntax.
    fun listWallets(): MutableList<String> {
        val pyNames = commands.callAttr("list_wallets")
        val names = ArrayList<String>()
        for (i in 0 until pyNames.callAttr("__len__").toJava(Int::class.java)) {
            val name = pyNames.callAttr("__getitem__", i).toString()
            names.add(name)
        }
        return names
    }

    /** If the password is wrong, throws PyException with the type InvalidPassword. */
    fun loadWallet(name: String, password: String?) {
        val prevName = walletName.value
        commands.callAttr("load_wallet", name, password)
        if (prevName != null && prevName != name) {
            commands.callAttr("close_wallet", prevName)
        }
    }

    fun makeTx(address: String, amount: Long?, password: String? = null,
               unsigned: Boolean = false): PyObject {
        makeAddress(address)

        val amountStr: String
        if (amount == null) {
            amountStr = "!"
        } else {
            if (amount <= 0) throw ToastException(R.string.Invalid_amount)
            amountStr = formatSatoshis(amount, UNIT_BCH)
        }

        val outputs = arrayOf(arrayOf(address, amountStr))
        try {
            return commands.callAttr("_mktx", outputs, Kwarg("password", password),
                                     Kwarg("unsigned", unsigned))
        } catch (e: PyException) {
            throw if (e.message!!.startsWith("NotEnoughFunds"))
                ToastException(R.string.insufficient_funds) else e
        }
    }

    fun makeAddress(addrStr: String): PyObject {
        if (addrStr.isEmpty()) {
            throw ToastException(R.string.enter_or)
        }
        try {
            return clsAddress.callAttr("from_string", addrStr)
        } catch (e: PyException) {
            throw if (e.message!!.startsWith("AddressError"))
                ToastException(R.string.invalid_address) else e
        }
    }
}


data class NetworkStatus(val localHeight: Int, val serverHeight: Int)
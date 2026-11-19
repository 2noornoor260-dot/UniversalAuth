package ax.nd.faceunlock

import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import ax.nd.faceunlock.service.FaceAuthServiceCallbacks
import ax.nd.faceunlock.service.FaceAuthServiceController

class FaceAuthActivity : AppCompatActivity(), FaceAuthServiceCallbacks {
    private var controller: FaceAuthServiceController? = null
    private var startTime: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_face_auth)
        controller = FaceAuthServiceController(this, this)
    }


    override fun onStart() {
        super.onStart()
        startTime = System.currentTimeMillis()
        controller?.start()
    }

    override fun onStop() {
        super.onStop()
        controller?.stop()
    }

    companion object {
        private const val TAG = "FaceAuthActivity"
    }

    // FaceAuthService can deliver these callbacks from its camera/work threads (e.g. cancel() from
    // onStop), so always hop to the UI thread before touching views or finishing the activity.
    override fun onAuthed() {
        runOnUiThread {
            val time = System.currentTimeMillis() - startTime
            Toast.makeText(this, "Authentication successful (took: ${time}ms)!", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onError(errId: Int, message: String) {
        runOnUiThread {
            findViewById<TextView>(R.id.statusText).text = message
        }
    }
}
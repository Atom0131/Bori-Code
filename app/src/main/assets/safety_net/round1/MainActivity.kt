package {{PACKAGE}}

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.DecimalFormat

class MainActivity : AppCompatActivity() {

    private lateinit var display: TextView
    private var currentInput = StringBuilder()
    private var operator = ""
    private var firstOperand: Double? = null
    private var newInput = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        display = findViewById(R.id.display)

        listOf(
            R.id.btn0 to "0", R.id.btn1 to "1", R.id.btn2 to "2",
            R.id.btn3 to "3", R.id.btn4 to "4", R.id.btn5 to "5",
            R.id.btn6 to "6", R.id.btn7 to "7", R.id.btn8 to "8",
            R.id.btn9 to "9", R.id.btnDot to ".",
        ).forEach { (id, v) -> findViewById<Button>(id).setOnClickListener { appendDigit(v) } }

        mapOf(
            R.id.btnAdd to "+", R.id.btnSub to "-",
            R.id.btnMul to "×", R.id.btnDiv to "÷",
        ).forEach { (id, op) -> findViewById<Button>(id).setOnClickListener { setOperator(op) } }

        findViewById<Button>(R.id.btnEquals).setOnClickListener { calculate() }
        findViewById<Button>(R.id.btnClear).setOnClickListener { clear() }
    }

    private fun appendDigit(value: String) {
        if (value == "." && currentInput.contains(".")) return
        if (newInput) { currentInput.clear(); newInput = false }
        currentInput.append(value)
        display.text = currentInput
    }

    private fun setOperator(op: String) {
        firstOperand = currentInput.toString().toDoubleOrNull() ?: firstOperand
        operator = op
        newInput = true
    }

    private fun calculate() {
        val second = currentInput.toString().toDoubleOrNull() ?: return
        val first = firstOperand ?: return
        val result = when (operator) {
            "+" -> first + second
            "-" -> first - second
            "×" -> first * second
            "÷" -> if (second != 0.0) first / second else Double.NaN
            else -> return
        }
        display.text = DecimalFormat("#.##########").format(result)
        currentInput = StringBuilder(display.text)
        firstOperand = null
        operator = ""
        newInput = true
    }

    private fun clear() {
        currentInput.clear()
        firstOperand = null
        operator = ""
        newInput = false
        display.text = "0"
    }
}

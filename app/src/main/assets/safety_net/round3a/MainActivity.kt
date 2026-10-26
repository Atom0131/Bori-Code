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
    private var expression = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        display = findViewById(R.id.display)

        findViewById<Button>(R.id.btn0).setOnClickListener { appendDigit("0") }
        findViewById<Button>(R.id.btn1).setOnClickListener { appendDigit("1") }
        findViewById<Button>(R.id.btn2).setOnClickListener { appendDigit("2") }
        findViewById<Button>(R.id.btn3).setOnClickListener { appendDigit("3") }
        findViewById<Button>(R.id.btn4).setOnClickListener { appendDigit("4") }
        findViewById<Button>(R.id.btn5).setOnClickListener { appendDigit("5") }
        findViewById<Button>(R.id.btn6).setOnClickListener { appendDigit("6") }
        findViewById<Button>(R.id.btn7).setOnClickListener { appendDigit("7") }
        findViewById<Button>(R.id.btn8).setOnClickListener { appendDigit("8") }
        findViewById<Button>(R.id.btn9).setOnClickListener { appendDigit("9") }
        findViewById<Button>(R.id.btnDot).setOnClickListener { appendDigit(".") }
        findViewById<Button>(R.id.btnAdd).setOnClickListener { setOperator("+") }
        findViewById<Button>(R.id.btnSub).setOnClickListener { setOperator("-") }
        findViewById<Button>(R.id.btnMul).setOnClickListener { setOperator("*") }
        findViewById<Button>(R.id.btnDiv).setOnClickListener { setOperator("/") }
        findViewById<Button>(R.id.btnEquals).setOnClickListener { calculate() }
        findViewById<Button>(R.id.btnClear).setOnClickListener { clear() }

        if (savedInstanceState != null) {
            currentInput = StringBuilder(savedInstanceState.getString("currentInput") ?: "")
            expression = savedInstanceState.getString("expression") ?: ""
            operator = savedInstanceState.getString("operator") ?: ""
            newInput = savedInstanceState.getBoolean("newInput", true)
            if (savedInstanceState.containsKey("firstOperand")) {
                firstOperand = savedInstanceState.getDouble("firstOperand")
            }
            display.text = expression.ifEmpty { "0" }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("currentInput", currentInput.toString())
        outState.putString("expression", expression)
        outState.putString("operator", operator)
        outState.putBoolean("newInput", newInput)
        firstOperand?.let { outState.putDouble("firstOperand", it) }
    }

    private fun appendDigit(value: String) {
        if (value == "." && currentInput.contains(".")) return
        if (newInput) {
            currentInput.clear()
            newInput = false
            if (expression.contains("=")) expression = ""
        }
        currentInput.append(value)
        expression += value
        display.text = expression
    }

    private fun setOperator(op: String) {
        firstOperand = currentInput.toString().toDoubleOrNull() ?: firstOperand
        operator = op
        newInput = true
        if (expression.contains("=")) expression = currentInput.toString()
        val symbol = when (op) {
            "-" -> "−"
            "*" -> "×"
            "/" -> "÷"
            else -> "+"
        }
        expression += " $symbol "
        display.text = expression
    }

    private fun calculate() {
        val second = currentInput.toString().toDoubleOrNull() ?: return
        val first = firstOperand ?: return
        val result = when (operator) {
            "+" -> first + second
            "-" -> first - second
            "*" -> first * second
            "/" -> if (second != 0.0) first / second else Double.NaN
            else -> return
        }
        val text = DecimalFormat("#.##########").format(result)
        expression += " = $text"
        display.text = expression
        currentInput = StringBuilder(text)
        firstOperand = null
        operator = ""
        newInput = true
    }

    private fun clear() {
        currentInput.clear()
        firstOperand = null
        operator = ""
        newInput = true
        expression = ""
        display.text = "0"
    }
}

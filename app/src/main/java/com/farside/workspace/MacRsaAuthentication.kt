package com.farside.workspace

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** Advertised Apple security type 33, RSA1 + random AES credential envelope (non-SRP variant). */
internal object MacRsaAuthentication {
    fun authenticate(input:DataInputStream,output:DataOutputStream,username:String,password:String):ByteArray {
        val name=username.toByteArray(Charsets.UTF_8)
        val pass=password.toByteArray(Charsets.UTF_8)
        require(name.isNotEmpty() && name.size<64 && pass.isNotEmpty() && pass.size<64) { "Apple RSA1 credentials exceed the protocol slots" }
        val key=begin(input,output)
        val random=SecureRandom()
        val aes=ByteArray(16).also(random::nextBytes)
        val plain=ByteArray(128).also(random::nextBytes)
        name.copyInto(plain);plain[name.size]=0
        pass.copyInto(plain,64);plain[64+pass.size]=0
        try {
            val credentials=Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE,SecretKeySpec(aes,"AES")) }.doFinal(plain)
            val envelope=Cipher.getInstance("RSA/ECB/PKCS1Padding").apply { init(Cipher.ENCRYPT_MODE,key) }.doFinal(aes)
            val bytes=ByteArrayOutputStream()
            DataOutputStream(bytes).apply {
                write(byteArrayOf(1,0,82,83,65,49));writeShort(1);write(credentials);writeShort(1);write(envelope)
            }
            output.writeInt(bytes.size());output.write(bytes.toByteArray());output.flush()
            input.readInt() // Non-SRP proof placeholder, followed by SecurityResult.
            if(input.readInt()!=0) throw VncAuthenticationException()
            return aes
        } catch(e:Exception) { aes.fill(0);throw e }
        finally { plain.fill(0);pass.fill(0) }
    }
    internal fun begin(input:DataInputStream,output:DataOutputStream):RSAPublicKey {
        output.writeByte(33)
        output.writeInt(10);output.write(byteArrayOf(1,0,82,83,65,49,0,0,0,0));output.flush()
        val size=input.readInt()
        require(size in 6..16384) { "Invalid RSA1 public-key response size" }
        val response=DataInputStream(input.readNBytesCompat(size).inputStream())
        response.readUnsignedShort() // Direction/version prefix.
        val derSize=response.readInt()
        require(derSize in 128..8192 && derSize<=response.available()) { "Invalid RSA1 DER length" }
        val key=KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(response.readNBytesCompat(derSize))) as RSAPublicKey
        require(key.modulus.bitLength() in 2048..8192 && key.publicExponent.toInt()>1 && key.publicExponent.testBit(0)) { "Invalid RSA1 public key" }
        return key
    }
    private fun DataInputStream.readNBytesCompat(size:Int)=ByteArray(size).also(::readFully)
}

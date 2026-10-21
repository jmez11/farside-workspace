package com.farside.workspace

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import org.bouncycastle.crypto.agreement.srp.SRP6StandardGroups
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.params.KeyParameter

/** Apple RSA1/SRP-6a variant; experimental until native handoff is verified. No auth retry. */
internal object MacSrpAuthentication {
    internal val group=SRP6StandardGroups.rfc5054_4096
    internal data class Challenge(val salt:ByteArray,val publicB:ByteArray,val iterations:Int,val options:ByteArray)
    internal data class Proof(val publicA:ByteArray,val client:ByteArray,val server:ByteArray,val key:ByteArray)
    private fun hash(vararg parts:ByteArray)=MessageDigest.getInstance("SHA-512").run { parts.forEach(::update);digest() }
    internal fun pad(number:BigInteger):ByteArray {
        val bytes=number.toByteArray().let { if(it.size==513 && it[0]==0.toByte()) it.copyOfRange(1,it.size) else it }
        require(number.signum()>=0 && bytes.size<=512)
        return ByteArray(512).also { bytes.copyInto(it,512-bytes.size) }
    }
    internal fun parseChallenge(bytes:ByteArray):Challenge {
        require(bytes.size in 1000..4096) { "Invalid Apple SRP challenge size" }
        val input=DataInputStream(bytes.inputStream())
        input.skipBytes(12)
        require(input.readUnsignedByte()==0) { "Invalid Apple SRP modulus encoding" }
        val modulus=ByteArray(512).also(input::readFully)
        require(MessageDigest.isEqual(modulus,pad(group.n))) { "Unsupported Apple SRP group" }
        val generatorLength=input.readUnsignedShort()
        require(generatorLength in 1..8)
        require(BigInteger(1,ByteArray(generatorLength).also(input::readFully))==group.g) { "Unsupported Apple SRP generator" }
        val saltLength=input.readUnsignedByte();require(saltLength in 16..64)
        val salt=ByteArray(saltLength).also(input::readFully)
        require(input.readUnsignedShort()==512) { "Invalid Apple SRP public value size" }
        val publicB=ByteArray(512).also(input::readFully)
        require(BigInteger(1,publicB).mod(group.n)!=BigInteger.ZERO) { "Invalid Apple SRP public value" }
        val iterations=input.readLong();require(iterations in 1..1_000_000) { "Invalid Apple SRP iteration count" }
        val optionLength=input.readUnsignedShort();require(optionLength in 1..256 && optionLength<=input.available())
        val options=ByteArray(optionLength).also(input::readFully)
        return Challenge(salt,publicB,iterations.toInt(),options)
    }
    internal fun solve(challenge:Challenge,password:String,secret:ByteArray):Proof {
        val n=group.n;val g=group.g
        val a=BigInteger(1,secret).mod(n.subtract(BigInteger.ONE)).add(BigInteger.ONE)
        val publicA=pad(g.modPow(a,n))
        val passwordBytes=password.toByteArray(Charsets.UTF_8)
        val generator=PKCS5S2ParametersGenerator(SHA512Digest())
        generator.init(passwordBytes,challenge.salt,challenge.iterations)
        val stretched=(generator.generateDerivedParameters(1024) as KeyParameter).key
        val x=try { BigInteger(1,hash(challenge.salt,hash(byteArrayOf(58),stretched))).mod(n) }
            finally { passwordBytes.fill(0);stretched.fill(0) }
        val u=BigInteger(1,hash(publicA,challenge.publicB));require(u.signum()!=0)
        val k=BigInteger(1,hash(pad(n),pad(g)))
        val base=BigInteger(1,challenge.publicB).subtract(k.multiply(g.modPow(x,n))).mod(n)
        require(base.signum()!=0) { "Invalid Apple SRP premaster value" }
        val session=pad(base.modPow(a.add(u.multiply(x)),n))
        val key=try { hash(session) } finally { session.fill(0) }
        val hn=hash(pad(n));val hg=hash(pad(g))
        val client=hash(ByteArray(64) { (hn[it].toInt() xor hg[it].toInt()).toByte() },hash(byteArrayOf()),challenge.salt,publicA,challenge.publicB,key)
        return Proof(publicA,client,hash(publicA,client,key),key)
    }
    private fun bytes(write:DataOutputStream.()->Unit):ByteArray=ByteArrayOutputStream().also { DataOutputStream(it).write() }.toByteArray()
    private fun packet(input:DataInputStream,max:Int):ByteArray {
        val size=input.readInt();require(size in 1..max) { "Invalid Apple SRP packet size" }
        return ByteArray(size).also(input::readFully)
    }
    internal fun verifyServer(bytes:ByteArray,expected:ByteArray) {
        // SASL outer step + u32 body-length-plus-four + u32 body-length.
        val input=DataInputStream(bytes.inputStream())
        // Current macOS returns u32 step/u16 outer/u32 inner (98 bytes total),
        // rather than the older documented u8 step/u32 outer/u32 inner form.
        val compact=bytes.size>=10 && java.nio.ByteBuffer.wrap(bytes).int==2
        val step=if(compact) input.readInt() else input.readUnsignedByte()
        val outer=if(compact) input.readUnsignedShort() else input.readInt()
        val inner=input.readInt()
        require(inner>=1+64+1+16+2+4 && outer==inner+4 && inner<=input.available()) {
            "Invalid Apple SRP server proof framing (bytes=${bytes.size}, step=$step, outer=$outer, inner=$inner, remaining=${input.available()})"
        }
        require(step==2 && inner==input.available()) { "Invalid Apple SRP server proof step/length" }
        require(input.readUnsignedByte()==64)
        val proof=ByteArray(64).also(input::readFully)
        if(!MessageDigest.isEqual(proof,expected)) throw MacHevcIntegrityException("Apple SRP server proof mismatch")
        require(input.readUnsignedByte()==16)
        input.skipBytes(16)
        require(input.readUnsignedShort()==0 && input.readInt()==0) { "Invalid Apple SRP server proof trailer" }
    }
    fun authenticate(input:DataInputStream,output:DataOutputStream,username:String,password:String):ByteArray {
        val name=username.toByteArray(Charsets.UTF_8)
        require(name.isNotEmpty() && name.size<=128 && password.isNotEmpty())
        val rsa=MacRsaAuthentication.begin(input,output)
        require(rsa.modulus.bitLength()==2048) { "Unsupported Apple SRP identity key size" }
        val identity=bytes { writeInt(7+name.size);writeInt(name.size);write(name);write(byteArrayOf(0,0,0)) }
        val encrypted=try { Cipher.getInstance("RSA/ECB/PKCS1Padding").run { init(Cipher.ENCRYPT_MODE,rsa);doFinal(identity) } } finally { identity.fill(0) }
        val first=bytes { write(byteArrayOf(1,0,82,83,65,49,0,2));writeShort(encrypted.size);write(encrypted);write(ByteArray(384)) }
        output.writeInt(first.size);output.write(first);output.flush()
        val challenge=parseChallenge(packet(input,4096))
        val random=SecureRandom()
        val secret=ByteArray(64).also(random::nextBytes)
        val proof=try { solve(challenge,password,secret) } finally { secret.fill(0) }
        try {
            val body=bytes {
                writeShort(512);write(proof.publicA);writeByte(64);write(proof.client)
                writeShort(challenge.options.size);write(challenge.options)
                writeByte(16);write(ByteArray(16).also(random::nextBytes))
            }
            val second=bytes { write(byteArrayOf(1,0,82,83,65,49,0,2));writeShort(body.size+4);writeInt(body.size);write(body) }
            require(second.size<=1076)
            output.writeInt(1076);output.write(second);output.write(ByteArray(1076-second.size));output.flush()
            val server=packet(input,4096)
            if(input.readInt()!=0) throw VncAuthenticationException()
            verifyServer(server,proof.server)
            return MessageDigest.getInstance("SHA-256").digest(proof.key).copyOf(16)
        } finally { proof.key.fill(0) }
    }
}

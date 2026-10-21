package com.farside.workspace

import java.io.*
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import org.junit.Assert.*
import org.junit.Test

class MacSrpAuthenticationTest {
    private fun h(vararg bytes:ByteArray)=MessageDigest.getInstance("SHA-512").run { bytes.forEach(::update);digest() }
    private fun bytes(block:DataOutputStream.()->Unit)=ByteArrayOutputStream().also { DataOutputStream(it).block() }.toByteArray()
    private val n=MacSrpAuthentication.group.n
    private val g=MacSrpAuthentication.group.g
    private fun pad(value:BigInteger)=value.toString(16).padStart(1024,'0').chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val salt=ByteArray(32) { (it+1).toByte() }
    private val options="mda=SHA-512,replay_detection,conf+int=ChaCha20-Poly1305,kdf=SALTED-SHA512-PBKDF2".toByteArray()
    private val password="synthetic test password"
    private val verifier:BigInteger get() {
        // Independent JCA password expansion; production uses BC and client-side SRP arithmetic.
        val spec=PBEKeySpec(password.toCharArray(),salt,1000,1024)
        val stretched=try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512").generateSecret(spec).encoded } finally { spec.clearPassword() }
        return g.modPow(BigInteger(1,h(salt,h(byteArrayOf(58),stretched))),n)
    }
    private val k:BigInteger get()=BigInteger(1,h(pad(n),pad(g)))
    private val b=BigInteger.valueOf(456789)
    private val publicB:ByteArray get()=pad(k.multiply(verifier).add(g.modPow(b,n)).mod(n))
    private fun challenge(modulus:ByteArray=pad(n),public:ByteArray=publicB,cost:Long=1000)=bytes {
        write(ByteArray(12));writeByte(0);write(modulus);writeShort(1);writeByte(5)
        writeByte(salt.size);write(salt);writeShort(public.size);write(public);writeLong(cost)
        writeShort(options.size);write(options)
    }
    private fun serverProof(a:ByteArray,client:ByteArray,key:ByteArray,compact:Boolean=true)=bytes {
        val inner=bytes { writeByte(64);write(h(a,client,key));writeByte(16);write(ByteArray(16));writeShort(0);writeInt(0) }
        if(compact) { writeInt(2);writeShort(inner.size+4) } else { writeByte(2);writeInt(inner.size+4) }
        writeInt(inner.size);write(inner)
    }
    @Test fun validatesGroupCostAndPublicValuesBeforePasswordProof() {
        val valid=challenge();assertEquals(1000,MacSrpAuthentication.parseChallenge(valid).iterations)
        listOf(challenge(cost=0),challenge(cost=1_000_001),challenge(public=ByteArray(512)),
            challenge(modulus=pad(n.subtract(BigInteger.TWO))),valid.copyOf(999)).forEach { bad ->
            assertThrows(IllegalArgumentException::class.java) { MacSrpAuthentication.parseChallenge(bad) }
        }
    }
    @Test fun independentServerMatchesClientSessionAndMutualProof() {
        val proof=MacSrpAuthentication.solve(MacSrpAuthentication.parseChallenge(challenge()),password,ByteArray(64) { it.toByte() })
        val a=BigInteger(1,proof.publicA);val u=BigInteger(1,h(proof.publicA,publicB))
        val key=h(pad(a.multiply(verifier.modPow(u,n)).mod(n).modPow(b,n)))
        assertArrayEquals(key,proof.key)
        val xor=h(pad(n)).zip(h(pad(g))) { x,y -> (x.toInt() xor y.toInt()).toByte() }.toByteArray()
        assertArrayEquals(h(xor,h(byteArrayOf()),salt,proof.publicA,publicB,key),proof.client)
        val server=serverProof(proof.publicA,proof.client,key)
        MacSrpAuthentication.verifyServer(server,proof.server)
        MacSrpAuthentication.verifyServer(serverProof(proof.publicA,proof.client,key,false),proof.server)
        server[20]=(server[20].toInt() xor 1).toByte()
        assertThrows(MacHevcIntegrityException::class.java) { MacSrpAuthentication.verifyServer(server,proof.server) }
    }
    @Test fun fullTranscriptChecksEncryptedIdentityAndServerProofWithoutFallback() {
        val pair=KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val serverIn=PipedInputStream(8192);val clientOut=DataOutputStream(PipedOutputStream(serverIn))
        val clientIn=PipedInputStream(8192);val serverOut=DataOutputStream(PipedOutputStream(clientIn))
        val server=CompletableFuture.supplyAsync {
            DataInputStream(serverIn).use { input ->
                assertEquals(33,input.readUnsignedByte());assertEquals(10,input.readInt());input.skipBytes(10)
                val pub=bytes { writeShort(1);writeInt(pair.public.encoded.size);write(pair.public.encoded) }
                serverOut.writeInt(pub.size);serverOut.write(pub);serverOut.flush()
                assertEquals(650,input.readInt());input.skipBytes(8);assertEquals(256,input.readUnsignedShort())
                val encrypted=ByteArray(256).also(input::readFully);input.skipBytes(384)
                val identity=Cipher.getInstance("RSA/ECB/PKCS1Padding").run { init(Cipher.DECRYPT_MODE,pair.private);doFinal(encrypted) }
                DataInputStream(identity.inputStream()).use { name ->
                    assertEquals(identity.size-4,name.readInt());val size=name.readInt()
                    assertEquals("short-user",String(ByteArray(size).also(name::readFully)));assertEquals(3,name.available())
                }
                val c=challenge();serverOut.writeInt(c.size);serverOut.write(c);serverOut.flush()
                assertEquals(1076,input.readInt());val raw=ByteArray(1076).also(input::readFully)
                val proof=DataInputStream(raw.inputStream());proof.skipBytes(8)
                val length=proof.readUnsignedShort();assertEquals(length-4,proof.readInt())
                assertEquals(512,proof.readUnsignedShort());val a=ByteArray(512).also(proof::readFully)
                assertEquals(64,proof.readUnsignedByte());val m1=ByteArray(64).also(proof::readFully)
                assertEquals(options.size,proof.readUnsignedShort());assertArrayEquals(options,ByteArray(options.size).also(proof::readFully))
                assertEquals(16,proof.readUnsignedByte())
                val u=BigInteger(1,h(a,publicB));val key=h(pad(BigInteger(1,a).multiply(verifier.modPow(u,n)).mod(n).modPow(b,n)))
                val xor=h(pad(n)).zip(h(pad(g))) { x,y -> (x.toInt() xor y.toInt()).toByte() }.toByteArray()
                assertArrayEquals(h(xor,h(byteArrayOf()),salt,a,publicB,key),m1)
                val response=serverProof(a,m1,key)
                serverOut.writeInt(response.size);serverOut.write(response);serverOut.writeInt(0);serverOut.flush()
                MessageDigest.getInstance("SHA-256").digest(key).copyOf(16)
            }
        }
        try {
            val key=MacSrpAuthentication.authenticate(DataInputStream(clientIn),clientOut,"short-user",password)
            assertArrayEquals(server.get(10,TimeUnit.SECONDS),key)
        } finally { clientIn.close();clientOut.close();serverIn.close();serverOut.close() }
    }
}

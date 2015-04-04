package org.reveno.atp.core.serialization;

import static org.reveno.atp.utils.MeasureUtils.mb;

import java.util.HashMap;
import java.util.Map;

import org.reveno.atp.api.domain.RepositoryData;
import org.reveno.atp.core.api.TransactionCommitInfo;
import org.reveno.atp.core.api.TransactionCommitInfo.Builder;
import org.reveno.atp.core.api.channel.Buffer;
import org.reveno.atp.core.api.serialization.RepositoryDataSerializer;
import org.reveno.atp.core.api.serialization.TransactionInfoSerializer;

import com.dyuproject.protostuff.LinkedBuffer;
import com.dyuproject.protostuff.ProtostuffIOUtil;
import com.dyuproject.protostuff.Schema;
import com.dyuproject.protostuff.runtime.RuntimeSchema;

public class ProtostuffSerializer implements RepositoryDataSerializer, TransactionInfoSerializer {

	@Override
	public int getSerializerType() {
		return PROTO_TYPE;
	}
	
	@Override
	public void registerTransactionType(Class<?> txDataType) {
		registered.put(txDataType.hashCode(), new ProtoTransactionTypeHolder(txDataType, RuntimeSchema.getSchema(txDataType)));
	}

	@SuppressWarnings("unchecked")
	@Override
	public void serialize(TransactionCommitInfo info, Buffer buffer) {
		changeClassLoaderIfRequired();
		
		buffer.writeLong(info.getTransactionId());
		buffer.writeLong(info.getTime());
		buffer.writeInt(info.getVersion());
		buffer.writeInt(info.getTransactionCommits().length);
		
		for (Object tc : info.getTransactionCommits()) {
			int classHash = tc.getClass().hashCode();
			byte[] data = ProtostuffIOUtil.toByteArray(tc, (Schema<Object>)registered.get(classHash).schema, linkedBuff.get());
			
			buffer.writeInt(classHash);
			buffer.writeInt(data.length);
			buffer.writeBytes(data);
			linkedBuff.get().clear();
		}
	}

	@SuppressWarnings("unchecked")
	@Override
	public TransactionCommitInfo deserialize(Builder builder, Buffer buffer) {
		changeClassLoaderIfRequired();
		
		long transactionId = buffer.readLong();
		long time = buffer.readLong();
		int version = buffer.readInt();
		Object[] commits = new Object[buffer.readInt()];
		
		for (int i = 0; i < commits.length; i++) {
			int classHash = buffer.readInt();
			byte[] data = buffer.readBytes(buffer.readInt());
			
			Schema<Object> schema = (Schema<Object>)registered.get(classHash).schema;
			Object message = schema.newMessage();
			ProtostuffIOUtil.mergeFrom(data, message, schema);
			commits[i] = message;
		}
		
		return builder.create(transactionId, version, time, commits);
	}

	@Override
	public void serialize(RepositoryData repository, Buffer buffer) {
		changeClassLoaderIfRequired();
		
		byte[] data = ProtostuffIOUtil.toByteArray(repository, repoSchema, linkedBuff.get());
		buffer.writeInt(data.length);
		buffer.writeBytes(data);
		linkedBuff.get().clear();
	}

	@Override
	public RepositoryData deserialize(Buffer buffer) {
		changeClassLoaderIfRequired();
		
		byte[] data = buffer.readBytes(buffer.readInt());
		RepositoryData repoData = repoSchema.newMessage();
		ProtostuffIOUtil.mergeFrom(data, repoData, repoSchema);
		return repoData;
	}
	
	
	public ProtostuffSerializer() {
		this(Thread.currentThread().getContextClassLoader());
	}
	
	public ProtostuffSerializer(ClassLoader classLoader) {
		this.classLoader = classLoader;
	}
	
	
	protected void changeClassLoaderIfRequired() {
		if (Thread.currentThread().getContextClassLoader() != classLoader) {
			Thread.currentThread().setContextClassLoader(classLoader);
		}
	}

	
	protected ThreadLocal<LinkedBuffer> linkedBuff = new ThreadLocal<LinkedBuffer>() {
		protected LinkedBuffer initialValue() {
			return LinkedBuffer.allocate(mb(1));
		};
	};
	protected ClassLoader classLoader;
	protected Map<Integer, ProtoTransactionTypeHolder> registered = new HashMap<>();
	protected final Schema<RepositoryData> repoSchema = RuntimeSchema.createFrom(RepositoryData.class);
	protected static final int PROTO_TYPE = 0x222;
	
	
	protected static class ProtoTransactionTypeHolder {
		public final Class<?> transactionType;
		public final Schema<?> schema;
		
		public ProtoTransactionTypeHolder(Class<?> transactionType, Schema<?> schema) {
			this.transactionType = transactionType;
			this.schema = schema;
		}
	}
	
}
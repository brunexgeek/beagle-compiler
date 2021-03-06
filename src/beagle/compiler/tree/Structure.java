package beagle.compiler.tree;

public class Structure extends TreeElement
{

	public TypeReference parent;

	//public StorageList storages = new StorageList();

	//public FunctionList functions = new FunctionList();

	public Name name;

	public TypeBody body;

	@Override
	public void accept(ITreeVisitor visitor)
	{
		if (visitor.visit(this))
		{
			visitor.visit(body);
			//for (StorageDeclaration item : storages)
			//	visitor.visit(item);
		}
		visitor.finish(this);
	}


}

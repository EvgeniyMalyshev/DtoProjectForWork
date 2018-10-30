package com.sbt.domain.plugin.dialog;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.PackageIndex;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.LabeledComponent;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.ui.components.panels.VerticalLayout;
import com.sbt.domain.plugin.DomainStorage;
import com.sbt.domain.plugin.PsiHelper;
import com.sbt.domain.plugin.annotation.JpaAnnotation;
import com.sbt.domain.plugin.node.DomainTreeNode;
import com.sbt.domain.plugin.node.NodeField;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DtoDialog extends DialogWrapper {

    private JPanel main;
    private DomainTreeNode node;
    private JTree domainTree;
    private JTree dtoTree;
    private JTextField dtoPackage;
    private JTextField dtoName;
    private List<NodeField> dtoFields = new ArrayList<>();
    private Project project;
    private JavaCodeStyleManager manager;
    private PsiElementFactory factory;
    private PsiClass entityClass;


    public DtoDialog(DomainTreeNode node) {
        super(node.getCurrentPsiClass().getProject());
        project = node.getCurrentPsiClass().getProject();
        entityClass = node.getCurrentPsiClass();

        this.node = node;
        setTitle("dto");
        main = new JPanel();
        domainTree = new JTree();
        dtoTree = new JTree();

        JPanel dtoConfig = createConfigPanel();

        main.add(domainTree);
        main.add(dtoConfig);
        main.add(dtoTree);

        DefaultMutableTreeNode domainRoot = new DefaultMutableTreeNode(node);
        for (NodeField field : node.getFields()) {
            domainRoot.add(new DefaultMutableTreeNode(field));
        }
        DefaultTreeModel treeModel = new DefaultTreeModel(domainRoot);
        domainTree.setModel(treeModel);
        domainTree.updateUI();

        updateDtoTree();

        domainTreeMouseListener();

        init();
    }

    @NotNull
    private JPanel createConfigPanel() {
        JPanel dtoConfig = new JPanel();
        dtoConfig.setLayout(new VerticalLayout(0));
        dtoPackage = new JTextField();
        dtoPackage.setText(fetchPackageName());
        dtoConfig.add(LabeledComponent.create(dtoPackage, "package"));
        dtoName = new JTextField();
        dtoName.setText(node.getName());
        dtoConfig.add(LabeledComponent.create(dtoName, "name"));
        JButton generate = new JButton();
        generate.setText("Generate");
        dtoConfig.add(generate);
        generate.addActionListener(e -> generateDto());
        return dtoConfig;
    }

    @NotNull
    private String fetchPackageName() {
        String qualifiedName = getNodeClassFullName();
        return qualifiedName.substring(0, qualifiedName.lastIndexOf("."));
    }

    private void generateDto() {
        VirtualFile[] directoriesByPackageName =
                PackageIndex.getInstance(project)
                        .getDirectoriesByPackageName(dtoPackage.getText(), true);
        PsiDirectory directory = PsiManager.getInstance(project)
                .findDirectory(directoriesByPackageName[0]);
        PsiClass dtoClass = JavaDirectoryService.getInstance().createClass(directory, dtoName.getText() + "DTO");
        PsiClass converterClassDto = JavaDirectoryService.getInstance().createClass(directory,
                dtoName.getText() + "ConverterToDTO");
        PsiClass converterClassEntity = JavaDirectoryService.getInstance().createClass(directory,
                dtoName.getText() + "ConverterToEntity");
        new WriteCommandAction.Simple(project, dtoClass.getContainingFile()) {
            @Override
            protected void run() throws Throwable {
                writeDto(dtoClass);
                writeConverterToDTO(converterClassDto, dtoClass);
                writeConverterToEntity(converterClassEntity, dtoClass);
            }
        }.execute();

    }

    private void writeDto(PsiClass dtoClass) {
        manager = JavaCodeStyleManager.getInstance(dtoClass.getProject());
        factory = JavaPsiFacade.getInstance(dtoClass.getProject()).getElementFactory();

        for (NodeField field : dtoFields) {
            String nameOfClass = cut(field.getPropertyType());
            String name = DomainDialog.toCamelCase(field.getName());
            //String name = field.getPropertyType();
            if (field.isCollection()) {
                manager.shortenClassReferences(
                        dtoClass.add(
                                factory.createFieldFromText(
                                        "private java.util.Collection<" + firstUpperCase(nameOfClass) +
                                                "DTO" +
                                                "> " + firstLowerCase(name) + ";"
                                        ,
                                        dtoClass)));
            } else if (field.isRelation()) {
                manager.shortenClassReferences(
                        dtoClass.add(
                                factory.createFieldFromText(
                                        "private " + field.getPropertyType() + "DTO " + field.getName() + ";"
                                        ,
                                        dtoClass)));
            } else {
                manager.shortenClassReferences(
                        dtoClass.add(
                                factory.createFieldFromText(
                                        "private " + field.getPropertyType() + " " + field.getName() + ";"
                                        ,
                                        dtoClass)));
            }
            manager.shortenClassReferences(
                    dtoClass.add(
                            factory.createMethodFromText(
                                    passPropertiesToGet(field)
                                    , dtoClass)));
            manager.shortenClassReferences(
                    dtoClass.add(
                            factory.createMethodFromText(
                                    passPropertiesToSet(field)
                                    , dtoClass)));
        }

    }

    private String passPropertiesToGet(NodeField field) {
        StringBuffer buffer = new StringBuffer();
        String name = DomainDialog.toCamelCase(field.getName());
        String nameOfClass = cut(field.getPropertyType());
        if (field.isCollection()) {
            buffer.append("public Collection<" + firstUpperCase(nameOfClass) + "DTO"
                    + "> get" + name + "() {\n return " + firstLowerCase(name) + "; }\n "
            );
        } else if (field.isRelation()) {
            buffer.append("public " + field.getPropertyType() + "DTO get" + name + "() {\n return " + field.getName() + "; }\n "
            );
        } else {
            buffer.append("public " + field.getPropertyType() + " get" + name + "() {\n return " + field.getName() + "; }\n "
            );
        }
        return buffer.toString();
    }

    private String passPropertiesToSet(NodeField field) {
        StringBuffer buffer = new StringBuffer();
        String name = DomainDialog.toCamelCase(field.getName());
        String nameOfClass = cut(field.getPropertyType());
        if (field.isCollection()) {
            buffer.append("public void " + "set" + firstUpperCase(name) + " (" + "Collection<" + firstUpperCase(nameOfClass) + "DTO"
                    + ">" + " " + firstLowerCase(name)
                    + ") {\n this." + firstLowerCase(name) + " = " + firstLowerCase(name) + "; }\n "
            );
        } else if (field.isRelation()) {
            buffer.append("public void " + " set" + name + "(" + field.getPropertyType() + "DTO " + field.getName() +
                    ") {\n this." + field.getName() + " = " + field.getName() + "; }\n "
            );
        } else {
            buffer.append("public void " + " set" + name + "(" + field.getPropertyType() + " " + field.getName() +
                    ") {\n this." + field.getName() + " = " + field.getName() + "; }\n "
            );
        }
        return buffer.toString();
    }

    private String createTextForConverterToDto() {
        StringBuffer buffer = new StringBuffer();
        for (NodeField field : dtoFields) {
            String name = DomainDialog.toCamelCase(field.getName());
            String nameOfClass = cut(field.getPropertyType());
            if (field.isRelation()) {
                PsiField psiField = (PsiField) field.getCurrentPsiElement();
                if (field.isDirectional()) {
                    for (PsiAnnotation a : psiField.getAnnotations()) {
                        if (a.getQualifiedName().equals(JpaAnnotation.ManyToMany.getFull())) {

                            buffer.append(
                                    "//This is submissiv class; \n");


                        } else if (a.getQualifiedName().equals(JpaAnnotation.OneToOne.getFull())) {
                            buffer.append(
                                    "//This is submissiv class; \n");
                        }
                    }

                } else {
                    if (psiField.getAnnotation(JpaAnnotation.ManyToMany.getFull()) != null && psiField.getAnnotation(JpaAnnotation.JoinTable.getFull()) != null) {
                        List<NodeField> fieldsDependent = DomainStorage.getInstance().getDomainTreeRoot().findNodeInTree(PsiHelper.getCollectionGenericType(psiField).getCanonicalText()).getFields();
                        String nameDependentSuper = "";
                        for (NodeField fieldDependent : fieldsDependent) {
                            String nameDependent = DomainDialog.toCamelCase(fieldDependent.getName());
                            PsiField psiFieldDependent = (PsiField) fieldDependent.getCurrentPsiElement();
                            if (psiFieldDependent.getAnnotation(JpaAnnotation.ManyToMany.getFull()) != null) {
                                nameDependentSuper = nameDependent;
                            }
                        }
                        buffer.append(

                                "java.util.List<" + firstUpperCase(nameOfClass) + "DTO> " + firstLowerCase(name) + "DTOArrayList = new java.util.ArrayList<>();"
                                        + "for (" + firstUpperCase(nameOfClass) + " " + firstLowerCase(nameOfClass) + " : " + "entity.get" + firstUpperCase(name) + "()) \n{"
                                        + firstUpperCase(nameOfClass) + "DTO " + firstLowerCase(nameOfClass) + "DTO = converterManager.convert(" + firstLowerCase(nameOfClass) + ", " + firstUpperCase(nameOfClass) + "DTO.class);\n"
                                        + firstLowerCase(nameOfClass) + "DTO.set" + nameDependentSuper + "(java.util.Arrays.asList(dto)); \n"
                                        + firstLowerCase(name) + "DTOArrayList.add(" + firstLowerCase(nameOfClass) + "DTO);}"
                                        + "dto.set" + firstUpperCase(name) + "(" + firstLowerCase(name) + "DTOArrayList); \n");
                    }
                    for (PsiAnnotation a : psiField.getAnnotations()) {
                        if (a.getQualifiedName().equals(JpaAnnotation.OneToMany.getFull())) {
                            buffer.append(
                                    "java.util.List<" + firstUpperCase(nameOfClass) + "DTO> " + firstLowerCase(name) + "DTOArrayList = new java.util.ArrayList<>();"
                                            + "for (" + firstUpperCase(nameOfClass) + " " + firstLowerCase(name) + " : " + "entity.get" + firstUpperCase(name) + "()) \n{"
                                            + firstUpperCase(nameOfClass) + "DTO " + firstLowerCase(name) + "DTO = converterManager.convert(" + firstLowerCase(name) + ", " + firstUpperCase(nameOfClass) + "DTO.class);"
                                            + firstLowerCase(name) + "DTOArrayList.add(" + firstLowerCase(name) + "DTO); \n}");

                        } else if (a.getQualifiedName().equals(JpaAnnotation.ManyToOne.getFull())) {
                            buffer.append("//Place to hit you in a face \n");

                        }
//                        else if (a.getQualifiedName().equals(JpaAnnotation.ManyToMany.getFull())) {
//                            if (a.getQualifiedName().equals(JpaAnnotation.JoinColumn.getFull())){


//                            }buffer.append("//there is no JOIN_Column \n" );
//                            String mappedByValue = null;
//                            for (PsiNameValuePair vp: a.getParameterList().getAttributes()) {
//                                if (vp.getName().equals("mappedBy")) mappedByValue = vp.getLiteralValue();
//                            }
                        //  }
                        else if (a.getQualifiedName().equals(JpaAnnotation.OneToOne.getFull())) {
                            buffer.append(
                                    firstUpperCase(field.getPropertyType()) + "DTO " + firstLowerCase(name) + "DTO = converterManager.convert(entity.get" + firstUpperCase(name) + "(), " + firstUpperCase(field.getPropertyType()) + "DTO.class);\n"
                                            + firstLowerCase(name) + "DTO.set" + firstUpperCase(getNodeNameOfClass()) + "(dto);"
                                            + "dto.set" + firstUpperCase(name) + "(" + firstLowerCase(name) + "DTO);");
                        }
                    }
                }
            } else {
                buffer.append("dto." + "set" + firstUpperCase(name) + "(entity.get" + firstUpperCase(name) + "()); \n "
                );
            }
        }

        return buffer.toString();
    }

    private String creatуTextForConverterToEntity() {
        StringBuffer buffer = new StringBuffer();
        for (NodeField field : dtoFields) {
            String name = field.getName();
            String nameOfClass = cut(field.getPropertyType());
            if (field.isRelation()) {
                PsiField psiField = (PsiField) field.getCurrentPsiElement();
                if (field.isDirectional()) {
                    for (PsiAnnotation a : ((PsiField) field.getCurrentPsiElement()).getAnnotations()) {
                        if (a.getQualifiedName().equals(JpaAnnotation.ManyToMany.getFull())) {
                            buffer.append("//This is submissiv class; \n"
                            );

                        } else if (a.getQualifiedName().equals(JpaAnnotation.OneToOne.getFull())) {
                            buffer.append(
                                    "//This is submissiv class; \n");
                        }
                    }
                } else {
                    for (PsiAnnotation a : ((PsiField) field.getCurrentPsiElement()).getAnnotations()) {
                        if (a.getQualifiedName().equals(JpaAnnotation.OneToMany.getFull())) {
                            String mappedByValue = null;
                            for (PsiNameValuePair vp : a.getParameterList().getAttributes()) {
                                if (vp.getName().equals("mappedBy")) mappedByValue = vp.getLiteralValue();
                            }
                            buffer.append(
                                    "java.util.List<" + firstUpperCase(nameOfClass) + "> " + firstLowerCase(nameOfClass) + "ArrayList = new java.util.ArrayList<>();"
                                            + "for (" + firstUpperCase(nameOfClass) + "DTO " + firstLowerCase(nameOfClass) + "DTO : " + "dto.get" + firstUpperCase(name) + "()) \n{"
                                            + firstUpperCase(nameOfClass) + " " + firstLowerCase(nameOfClass) + " = converterManager.convert(" + firstLowerCase(nameOfClass) + "DTO, " + firstUpperCase(nameOfClass) + ".class);\n"
                                            + firstLowerCase(nameOfClass) + ".set" + firstUpperCase(mappedByValue) + "(entity);"
                                            + firstLowerCase(nameOfClass) + "ArrayList.add(" + firstLowerCase(nameOfClass) + "); \n}"
                                            + "entity.set" + firstUpperCase(name) + "(" + firstLowerCase(nameOfClass) + "ArrayList);"
                            );

                        } else if (a.getQualifiedName().equals(JpaAnnotation.ManyToOne.getFull())) {
                            buffer.append("//Place to hit you in a face \n");

                        } else if (psiField.getAnnotation(JpaAnnotation.ManyToMany.getFull()) != null && psiField.getAnnotation(JpaAnnotation.JoinTable.getFull()) != null) {
                            List<NodeField> fieldsDependent = DomainStorage.getInstance().getDomainTreeRoot().findNodeInTree(PsiHelper.getCollectionGenericType(psiField).getCanonicalText()).getFields();
                            String nameDependentSuper = "";
                            for (NodeField fieldDependent : fieldsDependent) {
                                String nameDependent = DomainDialog.toCamelCase(fieldDependent.getName());
                                PsiField psiFieldDependent = (PsiField) fieldDependent.getCurrentPsiElement();
                                if (psiFieldDependent.getAnnotation(JpaAnnotation.ManyToMany.getFull()) != null) {
                                    nameDependentSuper = nameDependent;
                                }
                            }
                            buffer.append(
                                    "java.util.List<" + firstUpperCase(nameOfClass) + "> " + firstLowerCase(name) + "ArrayList = new java.util.ArrayList<>();"
                                            + "for (" + firstUpperCase(nameOfClass) + "DTO " + firstLowerCase(nameOfClass) + "DTO : " + "dto.get" + firstUpperCase(name) + "()) \n{"
                                            + firstUpperCase(nameOfClass) + " " + firstLowerCase(nameOfClass) + " = converterManager.convert(" + firstLowerCase(nameOfClass) + "DTO, " + firstUpperCase(nameOfClass) + ".class);\n"
                                            + firstLowerCase(nameOfClass) + ".set" + nameDependentSuper + "(java.util.Arrays.asList(entity)); \n"
                                            + firstLowerCase(name) + "ArrayList.add(" + firstLowerCase(nameOfClass) + ");} \n"
                                            + "entity.set" + firstUpperCase(name) + "(" + firstLowerCase(name) + "ArrayList); \n"
                            );

                        } else if (a.getQualifiedName().equals(JpaAnnotation.OneToOne.getFull())) {
                            buffer.append(
                                    firstUpperCase(name) + " " + firstLowerCase(name) + " = converterManager.convert(dto.get" + firstUpperCase(name) + "(), " + firstUpperCase(name) + ".class);\n"
                                            + firstLowerCase(name) + ".set" + firstUpperCase(getNodeNameOfClass()) + "(entity);"
                                            + "entity.set" + firstUpperCase(name) + "(" + firstLowerCase(name) + ");"
                            );
                        }

                    }
                }
            } else {
                buffer.append("entity." + "set" + firstUpperCase(name) + "(dto.get" + firstUpperCase(name) + "()); \n "
                );
            }
        }
        return buffer.toString();
    }


    private void writeConverterToDTO(PsiClass convertorClass, PsiClass dtoClass) {
        PsiClass classFromText = factory.createClassFromText("@org.springframework.stereotype.Component" +
                "\n public class " + dtoName.getText() + "ConverterToDTO implements " +
                "com.sbt.util.Converter<" +
                getNodeClassFullName() + ", " + dtoClass.getQualifiedName()
                + "> {\n\n}", null);
        convertorClass = (PsiClass) convertorClass.replace(
                PsiTreeUtil.findChildOfType(classFromText, PsiClass.class));
        manager.shortenClassReferences(convertorClass);
        manager.shortenClassReferences(
                convertorClass.add(
                        factory.createFieldFromText(
                                "@org.springframework.beans.factory.annotation.Autowired\n com.sbt.util.ConverterManager converterManager;"
                                , convertorClass)));
        convertorClass.add(
                factory.createMethodFromText(
                        "@Override\n" + "public " + dtoClass.getQualifiedName() + " convert(" + getNodeClassFullName() + " entity){\n"
                                + dtoClass.getQualifiedName() + " dto = new " + dtoClass.getQualifiedName() + "();"
                                + createTextForConverterToDto()
                                + "return dto; \n}"
                        , convertorClass));
        convertorClass.add(
                factory.createMethodFromText(
                        "@Override\n" + "public Class<" + getNodeClassFullName() + "> getSourceClass() {\n" +
                                "return " + getNodeClassFullName() + ".class;\n" + "}"
                        , convertorClass));
        convertorClass.add(
                factory.createMethodFromText(
                        "@Override\n" + "public Class<" + dtoClass.getQualifiedName() + "> getDestinationClass() {\n" +
                                "return " + dtoClass.getQualifiedName() + ".class;\n" + "}"
                        , convertorClass));
    }


    private void writeConverterToEntity(PsiClass convertorClass, PsiClass dtoClass) {
        PsiClass classFromText = factory.createClassFromText("@org.springframework.stereotype.Component" +
                "\npublic class " + dtoName.getText() + "ConverterToEntity implements " +
                "com.sbt.util.Converter<" +
                dtoClass.getQualifiedName() + ", " + getNodeClassFullName()
                + "> {\n\n}", null);
        convertorClass = (PsiClass) convertorClass.replace(
                PsiTreeUtil.findChildOfType(classFromText, PsiClass.class));
        manager.shortenClassReferences(convertorClass);
        manager.shortenClassReferences(
                convertorClass.add(
                        factory.createFieldFromText(
                                "@org.springframework.beans.factory.annotation.Autowired\n com.sbt.util.ConverterManager converterManager;"
                                , convertorClass)));
        convertorClass.add(
                factory.createMethodFromText(
                        "@Override\n" + "public " + getNodeClassFullName() + " convert("
                                + dtoClass.getQualifiedName() + " dto){\n" +
                                getNodeClassFullName() + " entity = new " + getNodeClassFullName() + "();\n"
                                + creatуTextForConverterToEntity()
                                + " return entity;\n }"
                        , convertorClass));
        convertorClass.add(
                factory.createMethodFromText(
                        "@Override\n" + "public Class<" + dtoClass.getQualifiedName() + "> getSourceClass() {\n" +
                                "return " + dtoClass.getQualifiedName() + ".class;\n" + "}"
                        , convertorClass));
        convertorClass.add(
                factory.createMethodFromText(
                        "@Override\n" + "public Class<" + getNodeClassFullName() + "> getDestinationClass() {\n" +
                                "return " + getNodeClassFullName() + ".class;\n" + "}"
                        , convertorClass));
    }


    private void createAnnotation(PsiClass aClass, String annotationText) {
        manager.shortenClassReferences(
                aClass.addBefore(factory.createAnnotationFromText(annotationText, aClass), aClass));
    }

    private void updateDtoTree() {
        DefaultMutableTreeNode domainRoot = new DefaultMutableTreeNode(dtoName.getText() + "Dto");
        DefaultTreeModel treeModel = new DefaultTreeModel(domainRoot);
        for (NodeField field : dtoFields) {
            domainRoot.add(new DefaultMutableTreeNode(field));
        }
        dtoTree.setModel(treeModel);
        dtoTree.updateUI();
    }

    private void domainTreeMouseListener() {
        domainTree.addMouseListener(new MouseListener() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() >= 1) {
                    addPropertytoDto();
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {

            }

            @Override
            public void mouseReleased(MouseEvent e) {

            }

            @Override
            public void mouseEntered(MouseEvent e) {

            }

            @Override
            public void mouseExited(MouseEvent e) {

            }
        });
    }

    private void addPropertytoDto() {
        DefaultMutableTreeNode treeModel = (DefaultMutableTreeNode) domainTree.getLastSelectedPathComponent();
        NodeField field = (NodeField) treeModel.getUserObject();

        if (dtoFields.contains(field))
            return;
        dtoFields.add(field);
        updateDtoTree();
    }

    public String firstUpperCase(String word) {
        if (word == null || word.isEmpty()) {
            return word;
        }
        return word.substring(0, 1).toUpperCase() + word.substring(1);
    }

    public String firstLowerCase(String word) {
        if (word == null || word.isEmpty()) {
            return word;
        }
        return word.substring(0, 1).toLowerCase() + word.substring(1);
    }

    private String cut(String name) {
        Pattern pattern = Pattern.compile(".+<(.+)>");
        Matcher matcher = pattern.matcher(name);
        while (matcher.find()) {
            return matcher.group(1);
        }
        return "";
    }

    private String getNodeClassFullName() {
        return ((PsiClass) node.getCurrentPsiClass()).getQualifiedName();
    }

    private String getNodeNameOfClass() {
        return ((PsiClass) node.getCurrentPsiClass()).getName();
    }


    @Nullable
    @Override
    protected JComponent createCenterPanel() {
        return new JScrollPane(main);
    }
}


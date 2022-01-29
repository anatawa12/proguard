/*
 * ProGuard -- shrinking, optimization, obfuscation, and preverification
 *             of Java bytecode.
 *
 * Copyright (c) 2002-2020 Guardsquare NV
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 2 of the License, or (at your option)
 * any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA
 */
package proguard.obfuscate;

import proguard.classfile.*;
import proguard.classfile.attribute.*;
import proguard.classfile.attribute.visitor.*;
import proguard.classfile.constant.ClassConstant;
import proguard.classfile.constant.visitor.ConstantVisitor;
import proguard.classfile.kotlin.visitor.ReferencedKotlinMetadataVisitor;
import proguard.classfile.kotlin.visitor.KotlinMetadataToClazzVisitor;
import proguard.classfile.kotlin.visitor.filter.KotlinSyntheticClassKindFilter;
import proguard.classfile.visitor.*;

/**
 * This <code>ClassVisitor</code> comes up with obfuscated names for the
 * classes it visits, and for their class members. The actual renaming is
 * done afterward.
 *
 * @see proguard.obfuscate.ClassRenamer
 *
 * @author Eric Lafortune
 */
public class ClassObfuscator
implements   ClassVisitor,
             AttributeVisitor,
             InnerClassesInfoVisitor,
             ConstantVisitor
{
    private final boolean               useMixedCaseClassNames;
    private final boolean               adaptKotlin;

    // Field acting as temporary variables and as return values for names
    // of outer classes and types of inner classes.
    private String  newClassName;
    private boolean numericClassName;

    private final NameObfuscator obfuscator;

    /**
     * Creates a new ClassObfuscator.
     * @param useMixedCaseClassNames  specifies whether obfuscated packages and
     *                                classes can get mixed-case names.
     * @param adaptKotlin             specifies whether Kotlin should be supported.
     */
    public ClassObfuscator(boolean useMixedCaseClassNames,
                           boolean adaptKotlin,
                           NameObfuscator obfuscator)
    {
        this.useMixedCaseClassNames  = useMixedCaseClassNames;
        this.adaptKotlin             = adaptKotlin;

        this.obfuscator = obfuscator;
    }


    // Implementations for ClassVisitor.

    @Override
    public void visitAnyClass(Clazz clazz)
    {
        throw new UnsupportedOperationException(this.getClass().getName() + " does not support " + clazz.getClass().getName());
    }


    @Override
    public void visitProgramClass(ProgramClass programClass)
    {
        // Does this class still need a new name?
        newClassName = newClassName(programClass);
        if (newClassName == null)
        {
            // Make sure the outer class has a name, if it exists. The name will
            // be stored as the new class name, as a side effect, so we'll be
            // able to use it as a prefix.
            programClass.attributesAccept(this);

            // Figure out a package prefix. The package prefix may actually be
            // the an outer class prefix, if any, or it may be the fixed base
            // package, if classes are to be repackaged.
            if (newClassName != null) {
                // Come up with a new class name, numeric or ordinary.
                if (numericClassName) {
                    newClassName = obfuscator.generateNumericClassName(newClassName, programClass);
                } else {
                    newClassName = obfuscator.generateInnerClassName(newClassName, programClass);
                }
            } else {
                // Come up with a new class name, numeric or ordinary.
                newClassName = obfuscator.generateClassName(programClass);
            }

            setNewClassName(programClass, newClassName);
        }
    }


    @Override
    public void visitLibraryClass(LibraryClass libraryClass)
    {
        // This can happen for dubious input, if the outer class of a program
        // class is a library class, and its name is requested.
        newClassName = libraryClass.getName();
    }


    // Implementations for AttributeVisitor.

    public void visitAnyAttribute(Clazz clazz, Attribute attribute) {}


    public void visitInnerClassesAttribute(Clazz clazz, InnerClassesAttribute innerClassesAttribute)
    {
        // Make sure the outer classes have a name, if they exist.
        innerClassesAttribute.innerClassEntriesAccept(clazz, this);
    }


    public void visitEnclosingMethodAttribute(Clazz clazz, EnclosingMethodAttribute enclosingMethodAttribute)
    {
        // Make sure the enclosing class has a name.
        enclosingMethodAttribute.referencedClassAccept(this);

        String innerClassName = clazz.getName();
        String outerClassName = clazz.getClassName(enclosingMethodAttribute.u2classIndex);

        numericClassName = isNumericClassName(clazz, innerClassName, outerClassName);
    }


    // Implementations for InnerClassesInfoVisitor.

    public void visitInnerClassesInfo(Clazz clazz, InnerClassesInfo innerClassesInfo)
    {
        // Make sure the outer class has a name, if it exists.
        int innerClassIndex = innerClassesInfo.u2innerClassIndex;
        int outerClassIndex = innerClassesInfo.u2outerClassIndex;
        if (innerClassIndex != 0 &&
            outerClassIndex != 0)
        {
            String innerClassName = clazz.getClassName(innerClassIndex);
            if (innerClassName.equals(clazz.getName()))
            {
                clazz.constantPoolEntryAccept(outerClassIndex, this);

                String outerClassName = clazz.getClassName(outerClassIndex);

                numericClassName = isNumericClassName(clazz, innerClassName, outerClassName);
            }
        }
    }


    /**
     * Returns whether the given class is a synthetic Kotlin lambda class.
     * We then know it's numeric.
     */
    private boolean isSyntheticKotlinLambdaClass(Clazz innerClass)
    {
        // Kotlin synthetic lambda classes that were named based on the
        // location that they were inlined from may be named like
        // OuterClass$methodName$1 where $methodName$1 is the inner class
        // name. We can rename this class to OuterClass$1 but the default
        // code below doesn't detect it as numeric.
        ClassCounter counter = new ClassCounter();
        innerClass.accept(
            new ReferencedKotlinMetadataVisitor(
            new KotlinSyntheticClassKindFilter(
                KotlinSyntheticClassKindFilter::isLambda,
                new KotlinMetadataToClazzVisitor(counter))));

        return counter.getCount() == 1;
    }


    /**
     * Returns whether the given inner class name is a numeric name.
     */
    private boolean isNumericClassName(Clazz  innerClass,
                                       String innerClassName,
                                       String outerClassName)
    {
        if (this.adaptKotlin && isSyntheticKotlinLambdaClass(innerClass))
        {
            return true;
        }

        int innerClassNameStart  = outerClassName.length() + 1;
        int innerClassNameLength = innerClassName.length();

        if (innerClassNameStart >= innerClassNameLength)
        {
            return false;
        }

        for (int index = innerClassNameStart; index < innerClassNameLength; index++)
        {
            if (!Character.isDigit(innerClassName.charAt(index)))
            {
                return false;
            }
        }

        return true;
    }


    // Implementations for ConstantVisitor.

    public void visitClassConstant(Clazz clazz, ClassConstant classConstant)
    {
        // Make sure the outer class has a name.
        classConstant.referencedClassAccept(this);
    }


    // Small utility methods.

    /**
     * Returns the given class name, unchanged if mixed-case class names are
     * allowed, or the lower-case version otherwise.
     */
    private String mixedCaseClassName(String className)
    {
        return useMixedCaseClassNames ?
            className :
            className.toLowerCase();
    }


    /**
     * Assigns a new name to the given class.
     * @param clazz the given class.
     * @param name  the new name.
     */
    public static void setNewClassName(Clazz clazz, String name)
    {
        clazz.setProcessingInfo(name);
    }


    /**
     * Returns whether the class name of the given class has changed.
     *
     * @param clazz the given class.
     * @return true if the class name is unchanged, false otherwise.
     */
    public static boolean hasOriginalClassName(Clazz clazz)
    {
        return clazz.getName().equals(newClassName(clazz));
    }


    /**
     * Retrieves the new name of the given class.
     * @param clazz the given class.
     * @return the class's new name, or <code>null</code> if it doesn't
     *         have one yet.
     */
    public static String newClassName(Clazz clazz)
    {
        Object processingInfo = clazz.getProcessingInfo();

        return processingInfo instanceof String ?
            (String)processingInfo :
            null;
    }
}
